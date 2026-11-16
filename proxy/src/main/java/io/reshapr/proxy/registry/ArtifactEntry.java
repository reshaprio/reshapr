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
package io.reshapr.proxy.registry;

import jakarta.annotation.Nullable;

import java.util.List;

public record ArtifactEntry(
      String id,
      String name,
      String path,
      ArtifactEntryType type,
      boolean mainArtifact,
      String content,
      List<SecretEntry> relatedSecrets) {

   public ArtifactEntry {
      relatedSecrets = relatedSecrets != null ? List.copyOf(relatedSecrets) : List.of();
   }

   public ArtifactEntry(String id, String name, String path, ArtifactEntryType type,
                        boolean mainArtifact, String content) {
      this(id, name, path, type, mainArtifact, content, List.of());
   }

   /**
    * Find a resolved secret attached to this artifact by its name.
    * @param secretName The secret name declared in the artifact content.
    * @return The matching {@link SecretEntry}, or {@code null} if not present.
    */
   public @Nullable SecretEntry findRelatedSecret(@Nullable String secretName) {
      if (secretName == null || secretName.isBlank()) {
         return null;
      }
      for (SecretEntry secret : relatedSecrets) {
         if (secretName.equals(secret.name())) {
            return secret;
         }
      }
      return null;
   }
}
