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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Content filter attached to an event endpoint, e.g. {@code filter=json:$.org==foobar}.
 * <p>
 * The syntax is {@code <kind>:<expression>}. Only the {@code json} kind is supported: a dotted path
 * from the payload root, compared with {@code ==} or {@code !=} to a literal value.
 * A message that is not JSON or does not hold the path never matches {@code ==}.
 * @author laurent
 */
public final class EventFilter {

   private static final ObjectMapper MAPPER = new ObjectMapper();

   private final List<Condition> conditions;

   private EventFilter(List<Condition> conditions) {
      this.conditions = conditions;
   }

   /**
    * Build a filter from subscription arguments (MCP Events): each argument is an equality condition on the
    * top-level payload property of the same name.
    * @return The filter, or null when there is no argument.
    */
   @Nullable
   public static EventFilter ofArguments(@Nullable Map<?, ?> arguments) {
      if (arguments == null || arguments.isEmpty()) {
         return null;
      }
      List<Condition> conditions = new ArrayList<>();
      arguments.forEach((name, value) ->
            conditions.add(new Condition(new String[] {String.valueOf(name)}, String.valueOf(value), false)));
      return new EventFilter(conditions);
   }

   /** Combine two optional filters: a message must satisfy both. */
   @Nullable
   public static EventFilter and(@Nullable EventFilter first,
                                 @Nullable EventFilter second) {
      if (first == null || second == null) {
         return first != null ? first : second;
      }
      List<Condition> conditions = new ArrayList<>(first.conditions);
      conditions.addAll(second.conditions);
      return new EventFilter(conditions);
   }

   /**
    * Parse a filter expression.
    * @param expression The value of the {@code filter} query parameter.
    * @return The filter, or null if the expression is blank.
    * @throws IllegalArgumentException if the syntax is not supported.
    */
   public static EventFilter parse(String expression) {
      if (expression == null || expression.isBlank()) {
         return null;
      }
      int colon = expression.indexOf(':');
      if (colon < 0 || !"json".equals(expression.substring(0, colon))) {
         throw new IllegalArgumentException("Unsupported event filter kind in '" + expression + "', expected 'json:'");
      }
      String body = expression.substring(colon + 1).trim();
      boolean negate = false;
      int op = body.indexOf("!=");
      if (op >= 0) {
         negate = true;
      } else {
         op = body.indexOf("==");
      }
      if (op < 0 || !body.startsWith("$.")) {
         throw new IllegalArgumentException("Invalid json event filter '" + body + "', expected $.path==value");
      }
      String[] path = body.substring(2, op).trim().split("\\.");
      String value = body.substring(op + 2).trim();
      if (value.length() >= 2 && (value.startsWith("'") && value.endsWith("'")
            || value.startsWith("\"") && value.endsWith("\""))) {
         value = value.substring(1, value.length() - 1);
      }
      return new EventFilter(List.of(new Condition(path, value, negate)));
   }

   public boolean matches(String payload) {
      JsonNode root;
      try {
         root = MAPPER.readTree(payload);
      } catch (Exception e) {
         // Not JSON: no value to compare, so only negated conditions can hold.
         root = null;
      }
      for (Condition condition : conditions) {
         if (!condition.matches(root)) {
            return false;
         }
      }
      return true;
   }

   private record Condition(String[] path, String expected, boolean negate) {
      boolean matches(JsonNode root) {
         JsonNode node = root;
         for (String segment : path) {
            node = node == null ? null : node.get(segment);
         }
         String actual = null;
         if (node != null && !node.isMissingNode() && !node.isNull()) {
            actual = node.isValueNode() ? node.asText() : node.toString();
         }
         return negate != expected.equals(actual);
      }
   }
}
