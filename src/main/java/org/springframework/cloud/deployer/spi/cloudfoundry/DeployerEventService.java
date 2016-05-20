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

import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;

/**
 * Service to publish various Spring {@link org.springframework.context.ApplicationEvent}s based on success or failure
 * of deployer functions.
 *
 * @author Greg Turnquist
 */
public class DeployerEventService implements ApplicationEventPublisherAware {

	private ApplicationEventPublisher publisher;

	@Override
	public void setApplicationEventPublisher(ApplicationEventPublisher publisher) {
		this.publisher = publisher;
	}

	public void appDeploymentSucceeded(String deployerId, List<String> details) {
		this.publisher.publishEvent(new DeploymentSucceeded(this, deployerId, details));
	}

	public void appDeploymentFailed(String deployerId, List<String> details) {
		this.publisher.publishEvent(new DeploymentFailed(this, deployerId, details));
	}

	public void appUndeploymentSucceeded(String deployerId, List<String> details) {
		this.publisher.publishEvent(new UndeploymentSucceeded(this, deployerId, details));
	}

	public void appUndeploymentFailed(String deployerId, List<String> details) {
		this.publisher.publishEvent(new UndeploymentFailed(this, deployerId, details));
	}
}
