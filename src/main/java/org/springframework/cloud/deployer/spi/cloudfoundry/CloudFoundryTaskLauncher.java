/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.deployer.spi.cloudfoundry;

import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v2.spaces.ListSpacesRequest;
import org.cloudfoundry.client.v3.Relationship;
import org.cloudfoundry.client.v3.applications.Application;
import org.cloudfoundry.client.v3.applications.CreateApplicationRequest;
import org.cloudfoundry.client.v3.applications.DeleteApplicationRequest;
import org.cloudfoundry.client.v3.applications.ListApplicationDropletsRequest;
import org.cloudfoundry.client.v3.applications.ListApplicationDropletsResponse;
import org.cloudfoundry.client.v3.applications.ListApplicationsRequest;
import org.cloudfoundry.client.v3.applications.ListApplicationsResponse;
import org.cloudfoundry.client.v3.droplets.Droplet;
import org.cloudfoundry.client.v3.droplets.GetDropletRequest;
import org.cloudfoundry.client.v3.packages.CreatePackageRequest;
import org.cloudfoundry.client.v3.packages.GetPackageRequest;
import org.cloudfoundry.client.v3.packages.Package;
import org.cloudfoundry.client.v3.packages.StagePackageRequest;
import org.cloudfoundry.client.v3.packages.UploadPackageRequest;
import org.cloudfoundry.client.v3.tasks.CancelTaskRequest;
import org.cloudfoundry.client.v3.tasks.CreateTaskRequest;
import org.cloudfoundry.client.v3.tasks.GetTaskRequest;
import org.cloudfoundry.client.v3.tasks.GetTaskResponse;
import org.cloudfoundry.client.v3.tasks.Task;
import org.cloudfoundry.util.PaginationUtils;
import org.cloudfoundry.util.ResourceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.task.LaunchState;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.cloud.deployer.spi.task.TaskStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;

import static org.cloudfoundry.util.DelayUtils.exponentialBackOff;
import static org.cloudfoundry.util.tuple.TupleUtils.function;

/**
 * @author Greg Turnquist
 */
public class CloudFoundryTaskLauncher implements TaskLauncher {

    private static final Logger logger = LoggerFactory
        .getLogger(CloudFoundryTaskLauncher.class);

    private final CloudFoundryClient client;

    public CloudFoundryTaskLauncher(CloudFoundryClient client) {
        this.client = client;
    }

    @Override
    public void cancel(String id) {

        asyncCancel(id).subscribe();
    }

    /**
     * Set up a reactor pipeline to launch a task. Before launch, check if it exists. If not, deploy. Then launch.
     *
     * @param request
     * @return name of the launched task, returned without waiting for reactor pipeline to complete
     */
    @Override
    public String launch(AppDeploymentRequest request) {

        asyncLaunch(request).subscribe();

        /**
         * The blocking API does NOT wait for async operations to complete before
         * returning
         */
        return request.getDefinition().getName();
    }

    /**
     * Lookup the current status based on task id.
     *
     * @param id
     * @return
     */
    @Override
    public TaskStatus status(String id) {

        return asyncStatus(id).get(Duration.ofSeconds(30));
    }

    Mono<Void> asyncCancel(String id) {

        return client.applicationsV3()
            .list(ListApplicationsRequest.builder()
                .name(id)
                .page(1)
                .build())
            .log("stream.listApplications")
            .flatMap(response -> Flux.fromIterable(response.getResources()))
            .log("stream.applications")
            .singleOrEmpty()
            .log("stream.singleOrEmpty")
            .map(Application::getId)
            .log("stream.taskIds")
            .then(taskId -> client.tasks()
                .cancel(CancelTaskRequest.builder()
                    .taskId(taskId)
                    .build())
                .log("stream.cancelTask"))
            .after();
    }

    Mono<String> asyncLaunch(AppDeploymentRequest request) {

        return deploy(request)
            .then(this::launchTask);
    }

    Mono<TaskStatus> asyncStatus(String id) {

        return client.applicationsV3()
            .list(ListApplicationsRequest.builder()
                .name(id)
                .page(1)
                .build())
            .log("stream.listApplications")
            .flatMap(response -> Flux.fromIterable(response.getResources()))
            .single()
            .map(Application::getId)
            .then(taskId -> client.tasks()
                .get(GetTaskRequest.builder()
                    .taskId(taskId)
                    .build()))
            .map(this::mapTaskToStatus)
            .otherwise(throwable -> {
                logger.error(throwable.getMessage());
                return Mono.just(new TaskStatus(id, LaunchState.unknown, null));
            });
    }

    /**
     * Create a new application using supplied {@link AppDeploymentRequest}.
     *
     * @param request
     * @return {@link Mono} containing the newly created {@link Droplet}'s id
     */
    Mono<String> createAndUploadApplication(AppDeploymentRequest request) {

        return createApplication(request.getDefinition().getName(), getSpaceId(request))
            .then(applicationId -> createPackage(applicationId)
                .and(Mono.just(applicationId)))
            .then(function((packageId, applicationId) -> uploadPackage(packageId, request)
                .and(Mono.just(applicationId))))
            .then(function((packageId, applicationId) -> waitForPackageProcessing(client, packageId)
                .and(Mono.just(applicationId))))
            .then(function((packageId, applicationId) -> createDroplet(packageId)
                .and(Mono.just(applicationId))))
            .then(function((dropletId, applicationId) -> waitForDropletProcessing(client, dropletId)
                .and(Mono.just(applicationId))))
            .map(function((dropletId, applicationId) -> applicationId));
    }

    private static Mono<String> waitForDropletProcessing(CloudFoundryClient cloudFoundryClient, String dropletId) {
        return cloudFoundryClient.droplets()
            .get(GetDropletRequest.builder()
                .dropletId(dropletId)
                .build())
            .log("stream.waitingForDroplet")
            .where(response -> !response.getState().equals("PENDING"))
            .repeatWhenEmpty(50, exponentialBackOff(Duration.ofSeconds(10), Duration.ofMinutes(1), Duration.ofMinutes(10)))
            .map(response -> dropletId);
    }

    private static Mono<String> waitForPackageProcessing(CloudFoundryClient cloudFoundryClient, String packageId) {
        return cloudFoundryClient.packages()
            .get(GetPackageRequest.builder()
                .packageId(packageId)
                .build())
            .where(response -> response.getState().equals("READY"))
            .repeatWhenEmpty(50, exponentialBackOff(Duration.ofSeconds(5), Duration.ofMinutes(1), Duration.ofMinutes(10)))
            .map(response -> packageId);
    }

    /**
     * Create a new Cloud Foundry application by name
     *
     * @param name
     * @param spaceId
     * @return applicationId
     */
    Mono<String> createApplication(String name, Mono<String> spaceId) {

        return spaceId
            .flatMap(spaceId2 -> client.applicationsV3()
                .create(CreateApplicationRequest.builder()
                    .name(name)
                    .relationship("space", Relationship.builder()
                        .id(spaceId2)
                        .build())
                    .build()))
            .single()
            .log("stream.createApplication")
            .map(Application::getId)
            .log("stream.getApplicationId");
    }

    /**
     * Create Cloud Foundry package by applicationId
     *
     * @param applicationId
     * @return packageId
     */
    Mono<String> createPackage(String applicationId) {

        return client.packages()
            .create(CreatePackageRequest.builder()
                .applicationId(applicationId)
                .type(CreatePackageRequest.PackageType.BITS)
                .build())
            .log("stream.createPackage")
            .map(Package::getId)
            .log("stream.getPackageId");
    }

    /**
     * Create an application with a package, then upload the bits into a staging.
     *
     * @param request
     * @return {@link Mono} with the applicationId
     */
    Mono<String> deploy(AppDeploymentRequest request) {
        return getApplicationId(client, request.getDefinition().getName())
            .then(applicationId -> getReadyApplicationId(client, applicationId)
                .otherwiseIfEmpty(deleteExistingApplication(client, applicationId)))
            .otherwiseIfEmpty(createAndUploadApplication(request));
    }

    Mono<String> getSpaceId(AppDeploymentRequest request) {

        return Mono
            .just(request.getEnvironmentProperties().get("organization"))
            .flatMap(organization -> PaginationUtils
                .requestResources(page -> client.spaces()
                    .list(ListSpacesRequest.builder()
                        .name(request.getEnvironmentProperties().get("space"))
                        .page(page)
                        .build())))
            .log("stream.listSpaces")
            .single()
            .log("stream.space")
            .map(ResourceUtils::getId)
            .log("stream.spaceId")
            .cache()
            .log("stream.cacheSpaceId");
    }

    /**
     * Create a new {@link Task} based on applicationId.
     *
     * @param applicationId
     * @return {@link Mono} containing name of the task that was launched
     */
    Mono<String> launchTask(String applicationId) {
        return client.tasks()
            .create(CreateTaskRequest.builder()
                .applicationId(applicationId)
                .name("timestamp")
                .command("java -jar")
                .build())
            .log("stream.createTask")
            .map(Task::getName)
            .log("stream.taskName");
    }

    /**
     * Upload bits to a Cloud Foundry application by packageId.
     *
     * @param packageId
     * @param request
     * @return packageId
     */
    Mono<String> uploadPackage(String packageId, AppDeploymentRequest request) {

        try {
            return client.packages()
                .upload(UploadPackageRequest.builder()
                    .packageId(packageId)
                    .bits(request.getResource().getInputStream())
                    .build())
                .log("stream.uploadPackage")
                .map(Package::getId)
                .log("stream.uploadedPackageId");
        } catch (IOException e) {
            return Mono.error(e);
        }
    }

    private static Mono<String> deleteExistingApplication(CloudFoundryClient client, String applicationId) {
        return requestDeleteApplication(client, applicationId)
            .after(Mono::empty);
    }

    /**
     * Look up the applicationId for a given app and confine results to 0 or 1 instance
     *
     * @param client
     * @param name
     * @return {@link Mono} with the application's id
     */
    private static Mono<String> getApplicationId(CloudFoundryClient client, String name) {

        return requestListApplications(client, name)
            .singleOrEmpty()
            .map(Application::getId);
    }

    private static Mono<String> getReadyApplicationId(CloudFoundryClient client, String applicationId) {
        return requestApplicationDroplets(client, applicationId)
            .filter(resource -> "STAGED" .equals(resource.getState()))
            .next()
            .map(resource -> applicationId);
    }

    private static Flux<ListApplicationDropletsResponse.Resource> requestApplicationDroplets(CloudFoundryClient client, String applicationId) {
        return client.applicationsV3()
            .listDroplets(ListApplicationDropletsRequest.builder()
                .applicationId(applicationId)
                .page(1)
                .build())
            .flatMap(response -> Flux.fromIterable(response.getResources()));
    }

    private static Mono<Void> requestDeleteApplication(CloudFoundryClient client, String applicationId) {
        return client.applicationsV3()
            .delete(DeleteApplicationRequest.builder()
                .applicationId(applicationId)
                .build());
    }

    /**
     * List ALL application entries filtered to the provided name
     *
     * @param client
     * @param name
     * @return {@link Flux} of application resources {@link ListApplicationsResponse.Resource}
     */
    private static Flux<ListApplicationsResponse.Resource> requestListApplications(
        CloudFoundryClient client, String name) {

        return client.applicationsV3()
            .list(ListApplicationsRequest.builder()
                .name(name)
                .page(1)
                .build())
            .log("stream.listApplications")
            .flatMap(response -> Flux.fromIterable(response.getResources()))
            .log("stream.applications");
    }

    /**
     * Create a new {@link Droplet} based upon packageId.
     *
     * @param packageId
     * @return {@link Mono} containing the {@link Droplet}'s ID.
     */
    private Mono<String> createDroplet(String packageId) {

        return client.packages()
            .stage(StagePackageRequest.builder()
                .packageId(packageId)
                .build())
            .log("stream.stageDroplet")
            .map(Droplet::getId)
            .log("stream.dropletId");
    }

    private TaskStatus mapTaskToStatus(GetTaskResponse getTaskResponse) {

        switch (getTaskResponse.getState()) {
            case Task.SUCCEEDED_STATE:
                return new TaskStatus(getTaskResponse.getId(), LaunchState.complete, null);
            case Task.RUNNING_STATE:
                return new TaskStatus(getTaskResponse.getId(), LaunchState.running, null);
            case Task.PENDING_STATE:
                return new TaskStatus(getTaskResponse.getId(), LaunchState.launching, null);
            case Task.CANCELING_STATE:
                return new TaskStatus(getTaskResponse.getId(), LaunchState.cancelled, null);
            case Task.FAILED_STATE:
                return new TaskStatus(getTaskResponse.getId(), LaunchState.failed, null);
            default:
                throw new IllegalStateException(
                    "Unsupported CF task state " + getTaskResponse.getState());
        }
    }

}
