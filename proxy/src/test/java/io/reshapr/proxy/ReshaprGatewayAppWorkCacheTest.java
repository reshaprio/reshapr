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

import io.reshapr.discovery.exposition.v1.ChangeType;
import io.reshapr.discovery.exposition.v1.Exposition;
import io.reshapr.discovery.exposition.v1.ExpositionChangeEvent;
import io.reshapr.discovery.exposition.v1.Service;
import io.reshapr.proxy.mcp.WorkCache;
import io.reshapr.proxy.registry.ArtifactEntry;
import io.reshapr.proxy.registry.ArtifactEntryType;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.ExpositionEntry;
import io.reshapr.proxy.registry.GatewayRegistry;
import io.reshapr.proxy.registry.SecretEntry;
import io.reshapr.proxy.registry.ServiceEntry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for the surgical work cache invalidation of {@link ReshaprGatewayApp}:
 * the work cache is keyed by {@code artifact.id()} and invalidated only for artifacts whose content actually
 * changed. Covers scenarios (b) content change invalidates only that artifact, (c) no content change
 * invalidates nothing, and (d) a DELETE invalidates nothing.
 * @author laurent
 */
class ReshaprGatewayAppWorkCacheTest {

   private static final String MINOR = "test-minor";

   private static ServiceEntry service() {
      return new ServiceEntry("svc-1", "acme", "Orders API", "1.0.0", "REST", List.of());
   }

   private static ConfigurationEntry config() {
      return new ConfigurationEntry("c1", "cfg", "http://backend", null,
            List.of(), List.of(), null, null, null);
   }

   private static ArtifactEntry artifact(String id, String content) {
      return new ArtifactEntry(id, id + ".yaml", null, ArtifactEntryType.OPEN_API_SPEC, true, content);
   }

   private static ArtifactEntry attached(String id, String content) {
      return new ArtifactEntry(id, id + ".yaml", null, ArtifactEntryType.RESHAPR_PROMPTS, false, content);
   }

   /** Build a bare app instance; only the registry and work cache are exercised by the tested paths. */
   private static ReshaprGatewayApp app(GatewayRegistry registry, WorkCache workCache) {
      return new ReshaprGatewayApp(null, null, registry, null, workCache);
   }

   /** (b) A content change on one artifact invalidates only that artifact's cache entries. */
   @Test
   void testContentChangeInvalidatesOnlyChangedArtifact() {
      GatewayRegistry registry = new GatewayRegistry();
      WorkCache cache = new WorkCache(1000);
      ReshaprGatewayApp app = app(registry, cache);

      ArtifactEntry mainV1 = artifact("main", "v1");
      ArtifactEntry attachedB1 = attached("att", "b1");
      ExpositionEntry previous = new ExpositionEntry("e1", null, service(), config(), mainV1, List.of(attachedB1));

      // Simulate parsed values cached under each artifact id.
      cache.set("main", MINOR, new Object());
      cache.set("att", MINOR, new Object());

      // The main artifact content changes (v1 -> v2), the attached one is unchanged.
      ArtifactEntry mainV2 = artifact("main", "v2");
      app.invalidateChangedArtifacts(previous, mainV2, List.of(attached("att", "b1")));

      assertNull(cache.get("main", MINOR), "changed artifact cache must be invalidated");
      assertNotNull(cache.get("att", MINOR), "unchanged artifact cache must be preserved");
   }

   /** (c) When no artifact content changed, nothing is invalidated. */
   @Test
   void testNoContentChangeInvalidatesNothing() {
      GatewayRegistry registry = new GatewayRegistry();
      WorkCache cache = new WorkCache(1000);
      ReshaprGatewayApp app = app(registry, cache);

      ArtifactEntry mainV1 = artifact("main", "v1");
      ExpositionEntry previous = new ExpositionEntry("e1", null, service(), config(), mainV1, List.of());

      cache.set("main", MINOR, new Object());

      // Same content on re-fetch.
      app.invalidateChangedArtifacts(previous, artifact("main", "v1"), List.of());

      assertNotNull(cache.get("main", MINOR), "unchanged artifact cache must be preserved");
   }

   /** (d) A DELETE event removes the exposition but never invalidates the work cache. */
   @Test
   void testDeleteInvalidatesNothing() {
      GatewayRegistry registry = new GatewayRegistry();
      WorkCache cache = new WorkCache(1000);
      ReshaprGatewayApp app = app(registry, cache);

      ServiceEntry service = service();
      ArtifactEntry main = artifact("main", "v1");
      registry.addExposition(new ExpositionEntry("e1", null, service, config(), main, List.of()));
      cache.set("main", MINOR, new Object());

      ExpositionChangeEvent event = ExpositionChangeEvent.newBuilder()
            .setChangeType(ChangeType.DELETED)
            .setExposition(Exposition.newBuilder()
                  .setId("e1")
                  .setService(Service.newBuilder()
                        .setId("svc-1").setName("Orders API").setVersion("1.0.0").build())
                  .build())
            .build();

      app.propagateExpositionChangeEvent(event);

      // The exposition is gone from the registry, but the artifact cache is untouched (content unchanged).
      assertNull(registry.getExpositionById("e1"), "the deleted exposition must be removed");
      assertNotNull(cache.get("main", MINOR), "DELETE must not invalidate shared artifact caches");
   }

   /** Updating one exposition must not evict cache entries of an artifact shared with another exposition. */
   @Test
   void testSharedArtifactPreservedAcrossExpositions() {
      GatewayRegistry registry = new GatewayRegistry();
      WorkCache cache = new WorkCache(1000);
      ReshaprGatewayApp app = app(registry, cache);

      ExpositionEntry e1 = new ExpositionEntry("e1", null, service(), config(),
            artifact("main1", "m1-v1"), List.of(attached("shared", "s-v1")));
      ExpositionEntry e2 = new ExpositionEntry("e2", null, service(), config(),
            artifact("main2", "m2-v1"), List.of(attached("shared", "s-v1")));
      registry.addExposition(e1);
      registry.addExposition(e2);

      cache.set("main1", MINOR, new Object());
      cache.set("main2", MINOR, new Object());
      cache.set("shared", MINOR, new Object());

      // e1 is updated: only its own main changes, the shared attached artifact is identical.
      app.invalidateChangedArtifacts(registry.getExpositionById("e1"),
            artifact("main1", "m1-v2"), List.of(attached("shared", "s-v1")));

      assertNull(cache.get("main1", MINOR), "e1's changed main must be invalidated");
      assertNotNull(cache.get("main2", MINOR), "e2's own artifact must be preserved");
      assertNotNull(cache.get("shared", MINOR), "shared unchanged artifact must be preserved for e2");
   }

   /** When the shared artifact itself changes, its cache is invalidated but e2's own artifacts are kept. */
   @Test
   void testSharedArtifactChangeInvalidatesOnlyShared() {
      GatewayRegistry registry = new GatewayRegistry();
      WorkCache cache = new WorkCache(1000);
      ReshaprGatewayApp app = app(registry, cache);

      registry.addExposition(new ExpositionEntry("e1", null, service(), config(),
            artifact("main1", "m1-v1"), List.of(attached("shared", "s-v1"))));
      registry.addExposition(new ExpositionEntry("e2", null, service(), config(),
            artifact("main2", "m2-v1"), List.of(attached("shared", "s-v1"))));

      cache.set("main1", MINOR, new Object());
      cache.set("main2", MINOR, new Object());
      cache.set("shared", MINOR, new Object());

      app.invalidateChangedArtifacts(registry.getExpositionById("e1"),
            artifact("main1", "m1-v1"), List.of(attached("shared", "s-v2")));

      assertNull(cache.get("shared", MINOR), "changed shared artifact must be invalidated");
      assertNotNull(cache.get("main1", MINOR), "unchanged main1 must be preserved");
      assertNotNull(cache.get("main2", MINOR), "e2's own artifact must be preserved");
   }

   /** A related-secret value change invalidates that artifact's cache even when YAML content is unchanged. */
   @Test
   void testRelatedSecretsChangeInvalidatesOnlyChangedArtifact() {
      GatewayRegistry registry = new GatewayRegistry();
      WorkCache cache = new WorkCache(1000);
      ReshaprGatewayApp app = app(registry, cache);

      SecretEntry secretV1 = new SecretEntry("docs-token", null, null, "tok-v1", null, null, false, null);
      SecretEntry secretV2 = new SecretEntry("docs-token", null, null, "tok-v2", null, null, false, null);

      ArtifactEntry mainV1 = artifact("main", "v1");
      ArtifactEntry skillsV1 = new ArtifactEntry("skills", "skills.yaml", null,
            ArtifactEntryType.RESHAPR_SKILLS, false, "skills-yaml", Map.of(secretV1.name(), secretV1));
      ExpositionEntry previous = new ExpositionEntry("e1", null, service(), config(), mainV1, List.of(skillsV1));

      cache.set("main", MINOR, new Object());
      cache.set("skills", MINOR, new Object());

      ArtifactEntry skillsV2 = new ArtifactEntry("skills", "skills.yaml", null,
            ArtifactEntryType.RESHAPR_SKILLS, false, "skills-yaml", Map.of(secretV2.name(), secretV2));
      app.invalidateChangedArtifacts(previous, artifact("main", "v1"), List.of(skillsV2));

      assertNotNull(cache.get("main", MINOR), "unchanged artifact cache must be preserved");
      assertNull(cache.get("skills", MINOR), "artifact with updated relatedSecrets must be invalidated");
   }
}

