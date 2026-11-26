/*
 * Copyright The Reshapr Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.reshapr.ctrl.rest;

import io.reshapr.ctrl.config.AuthenticationIdentityProviderConfig;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@RunOnVirtualThread
@Path("/api/config")
public class AppConfigurationResource {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   @ConfigProperty(name = "reshapr.mode")
   String mode;
   @ConfigProperty(name = "reshapr.version")
   String version;
   @ConfigProperty(name = "reshapr.buildTimestamp")
   String buildTimestamp;

   private final AuthenticationIdentityProviderConfig authenticationConfig;

   public AppConfigurationResource(AuthenticationIdentityProviderConfig authenticationConfig) {
      this.authenticationConfig = authenticationConfig;
   }


   @GET
   @Path("/")
   public BootstrapConfiguration getBootstrapConfiguration() {
      logger.debugf("Returning bootstrap configuration with mode %s", mode);
      return new BootstrapConfiguration(mode, version, buildTimestamp, authenticationConfig.enabled());
   }
}
