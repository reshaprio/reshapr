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
package io.reshapr.ctrl.artifacts;

import io.reshapr.ctrl.model.Artifact;
import io.reshapr.ctrl.model.ArtifactType;
import io.reshapr.ctrl.model.Operation;
import io.reshapr.ctrl.model.Service;
import io.reshapr.ctrl.model.ServiceType;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AsyncAPIImporter}.
 * @author laurent
 */
class AsyncAPIImporterTest {

   private static final String SPEC = "src/test/resources/io/reshapr/ctrl/artifacts/orders-asyncapi.yaml";

   @Test
   void testImportAsyncAPI() throws Exception {
      AsyncAPIImporter importer = new AsyncAPIImporter(new File(SPEC).getAbsolutePath(), null);

      List<Service> services = importer.getServiceDefinitions();
      assertEquals(1, services.size());

      Service service = services.getFirst();
      assertEquals("Orders Events API", service.name);
      assertEquals("1.0.0", service.version);
      assertEquals(ServiceType.ASYNC, service.type);
      assertEquals(2, service.operations.size());

      Operation send = service.operations.stream().filter(o -> o.name.equals("onOrderCreated")).findFirst().orElseThrow();
      assertEquals("SEND", send.method);
      assertEquals("orders.created", send.action);
      assertEquals("OrderCreated", send.outputName);

      Operation receive = service.operations.stream().filter(o -> o.name.equals("createOrder")).findFirst().orElseThrow();
      assertEquals("RECEIVE", receive.method);
      assertEquals("orders.commands.create", receive.action);

      List<Artifact> artifacts = importer.getArtifactDefinitions(service);
      assertEquals(1, artifacts.size());
      assertEquals(ArtifactType.ASYNC_API_SPEC, artifacts.getFirst().type);
      assertEquals("Orders Events API-1.0.0.yaml", artifacts.getFirst().name);
   }

   @Test
   void testFactoryDetectsAsyncAPI() throws Exception {
      ArtifactImporter importer = ArtifactImporterFactory.getArtifactImporter(new File(SPEC), null);
      assertInstanceOf(AsyncAPIImporter.class, importer);
   }
}
