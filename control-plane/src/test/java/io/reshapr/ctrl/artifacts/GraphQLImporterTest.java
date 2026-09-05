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
class GraphQLImporterTest {

    @Test
    void testGraphQLImporter() throws Exception {
        String path = new File("src/test/resources/io/reshapr/ctrl/artifacts/schema.graphql").getAbsolutePath();
        GraphQLImporter importer = new GraphQLImporter(path);
        
        importer.setServiceName("My GraphQL API");
        importer.setServiceVersion("1.0.0");

        List<Service> services = importer.getServiceDefinitions();
        assertNotNull(services);
        assertEquals(1, services.size());
        
        Service service = services.get(0);
        assertEquals("My GraphQL API", service.name);
        assertEquals("1.0.0", service.version);
        assertEquals(ServiceType.GRAPHQL, service.type);
        assertEquals(3, service.operations.size());

        List<Artifact> artifacts = importer.getArtifactDefinitions(service);
        assertNotNull(artifacts);
        assertEquals(1, artifacts.size());
        assertEquals("My GraphQL API-1.0.0.graphql", artifacts.get(0).name);
    }

    @Test
    void testGraphQLImporterMissingName() throws Exception {
        String path = new File("src/test/resources/io/reshapr/ctrl/artifacts/schema.graphql").getAbsolutePath();
        GraphQLImporter importer = new GraphQLImporter(path);
        
        assertThrows(ArtifactImportException.class, importer::getServiceDefinitions);
    }
}
