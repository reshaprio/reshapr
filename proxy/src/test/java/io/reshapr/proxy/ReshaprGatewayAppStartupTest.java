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
package io.reshapr.proxy;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.runtime.StartupEvent;
import io.reshapr.discovery.exposition.v1.ExpositionDiscoveryRequest;
import io.reshapr.discovery.exposition.v1.ExpositionDiscoveryServiceGrpc;
import io.reshapr.proxy.registry.GatewayRegistry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the startup error handling of {@link ReshaprGatewayApp}: an unreachable control plane
 * must abort startup (fail-fast) unless explicitly disabled, and must never mark the gateway as connected.
 * @author laurent
 */
class ReshaprGatewayAppStartupTest {

   private static ReshaprGatewayApp unreachableControlPlaneApp(boolean failFast) {
      ExpositionDiscoveryServiceGrpc.ExpositionDiscoveryServiceBlockingStub stub =
            mock(ExpositionDiscoveryServiceGrpc.ExpositionDiscoveryServiceBlockingStub.class);
      when(stub.discoverExpositions(any(ExpositionDiscoveryRequest.class)))
            .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

      ReshaprGatewayApp app = new ReshaprGatewayApp(stub, null, new GatewayRegistry(), null, null);
      app.gatewayId = "test-gateway";
      app.labels = Map.of();
      app.fqdns = List.of("localhost:7777");
      app.applicationVersion = "test";
      app.failFastOnStartup = failFast;
      return app;
   }

   @Test
   void testStartupFailsFastWhenControlPlaneUnreachable() {
      ReshaprGatewayApp app = unreachableControlPlaneApp(true);

      IllegalStateException e = assertThrows(IllegalStateException.class, () -> app.onStart(new StartupEvent()));

      assertSame(StatusRuntimeException.class, e.getCause().getClass());
      assertFalse(app.hasConnectedToControlPlane());
   }

   @Test
   void testStartupToleratesUnreachableControlPlaneWhenFailFastDisabled() {
      ReshaprGatewayApp app = unreachableControlPlaneApp(false);

      app.onStart(new StartupEvent());

      assertFalse(app.hasConnectedToControlPlane());
   }
}
