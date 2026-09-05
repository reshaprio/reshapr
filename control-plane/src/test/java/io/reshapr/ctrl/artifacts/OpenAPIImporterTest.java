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
import io.reshapr.ctrl.model.Service;
import io.reshapr.ctrl.model.ServiceType;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author vaishnav
 */
class OpenAPIImporterTest {

    @Test
    void testOpenAPIImporter() throws Exception {
        String path = new File("src/test/resources/io/reshapr/ctrl/artifacts/openapi.json").getAbsolutePath();
        OpenAPIImporter importer = new OpenAPIImporter(path, null);
        
        List<Service> services = importer.getServiceDefinitions();
        assertNotNull(services);
        assertEquals(1, services.size());
        
        Service service = services.get(0);
        assertEquals("My API", service.name);
        assertEquals("1.0.0", service.version);
        assertEquals(ServiceType.REST, service.type);
        assertEquals(3, service.operations.size());

        List<Artifact> artifacts = importer.getArtifactDefinitions(service);
        assertNotNull(artifacts);
        assertEquals(1, artifacts.size());
        assertEquals("My API-1.0.0.json", artifacts.get(0).name);
    }
}
