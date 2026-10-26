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
package io.reshapr.proxy.mcp.converters;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Maps OpenAPI parameter names to unique names accepted by MCP clients. */
final class McpParameterNames {

   private static final int MAX_LENGTH = 64;

   private McpParameterNames() {
   }

   static List<String> aliases(List<String> names) {
      List<String> aliases = new ArrayList<>(java.util.Collections.nCopies(names.size(), null));
      Set<String> used = new HashSet<>();

      // A valid OpenAPI name wins over a sanitized collision, regardless of declaration order.
      for (int index = 0; index < names.size(); index++) {
         if (isValid(names.get(index)) && used.add(names.get(index))) {
            aliases.set(index, names.get(index));
         }
      }
      for (int index = 0; index < names.size(); index++) {
         if (aliases.get(index) == null) {
            aliases.set(index, uniqueAlias(normalize(names.get(index)), used));
         }
      }
      return aliases;
   }

   private static boolean isValid(String name) {
      return name != null && name.matches("[a-zA-Z0-9._-]{1,64}");
   }

   private static String normalize(String name) {
      String normalized = name == null ? "" : name.replaceAll("[^a-zA-Z0-9._-]+", "_")
            .replaceAll("_+", "_").replaceAll("^_+|_+$", "");
      if (normalized.isEmpty()) {
         normalized = "param";
      }
      return normalized.substring(0, Math.min(normalized.length(), MAX_LENGTH));
   }

   private static String uniqueAlias(String candidate, Set<String> used) {
      if (used.add(candidate)) {
         return candidate;
      }
      for (int suffix = 2; ; suffix++) {
         String suffixValue = "_" + suffix;
         String alias = candidate.substring(0, Math.min(candidate.length(), MAX_LENGTH - suffixValue.length()))
               + suffixValue;
         if (used.add(alias)) {
            return alias;
         }
      }
   }
}
