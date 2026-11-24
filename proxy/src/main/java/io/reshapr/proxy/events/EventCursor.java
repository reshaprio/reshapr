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
package io.reshapr.proxy.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Opaque Events cursor: a destination and a broker-specific position token (see {@link EventPosition}). It only
 * refers to broker positions, so any proxy replica can resume from it. It is bound to its destination.
 * @author laurent
 */
public final class EventCursor {

   private static final ObjectMapper MAPPER = new ObjectMapper();
   private static final int VERSION = 1;

   private EventCursor() {
   }

   public static String encode(String destination, String position) {
      try {
         String json = MAPPER.writeValueAsString(Map.of("v", VERSION, "d", destination, "p", position));
         return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
      } catch (Exception e) {
         throw new IllegalStateException(e);
      }
   }

   /**
    * Decode a cursor.
    * @return The position token, or null for a null/blank cursor ("from now").
    * @throws IllegalArgumentException if the cursor is malformed or was issued for another destination.
    */
   @Nullable
   public static String decode(@Nullable String cursor, String destination) {
      if (cursor == null || cursor.isBlank()) {
         return null;
      }
      JsonNode tree;
      try {
         tree = MAPPER.readTree(Base64.getUrlDecoder().decode(cursor));
      } catch (Exception e) {
         throw new IllegalArgumentException("Invalid cursor");
      }
      if (tree.path("v").asInt() != VERSION || !tree.path("p").isTextual()) {
         throw new IllegalArgumentException("Invalid cursor");
      }
      if (!destination.equals(tree.path("d").asText())) {
         throw new IllegalArgumentException("Cursor was issued for another event type");
      }
      return tree.get("p").asText();
   }
}