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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.reshapr.proxy.mcp.converters.OpenAPIQueryParameterSerializer.QueryParameter;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link OpenAPIQueryParameterSerializer}.
 * @author vaishnav
 */
class OpenAPIQueryParameterSerializerTest {

   private final ObjectMapper mapper = new ObjectMapper();
   private final OpenAPIQueryParameterSerializer serializer = new OpenAPIQueryParameterSerializer(mapper);

   @Test
   void testFormArrayExplodedDefault() {
      // tags, form, explode=true (query defaults) -> tags=red & tags=blue
      ObjectNode parameter = mapper.createObjectNode().put("name", "tags").put("style", "form");
      ObjectNode schema = mapper.createObjectNode().put("type", "array");

      List<QueryParameter> result = serializer.serialize(parameter, schema, List.of("red", "blue"));
      assertEquals(2, result.size());
      assertEquals(new QueryParameter("tags", "red"), result.get(0));
      assertEquals(new QueryParameter("tags", "blue"), result.get(1));
   }

   @Test
   void testFormArrayUnexploded() {
      // tags, form, explode=false -> tags=red,blue
      ObjectNode parameter = mapper.createObjectNode().put("name", "tags").put("style", "form").put("explode", false);
      ObjectNode schema = mapper.createObjectNode().put("type", "array");

      List<QueryParameter> result = serializer.serialize(parameter, schema, List.of("red", "blue"));
      assertEquals(List.of(new QueryParameter("tags", "red,blue")), result);
   }

   @Test
   void testFormObjectExploded() {
      // filter, form, explode=true -> equals=shoes
      ObjectNode parameter = mapper.createObjectNode().put("name", "filter").put("style", "form").put("explode", true);
      ObjectNode schema = mapper.createObjectNode().put("type", "object");

      List<QueryParameter> result = serializer.serialize(parameter, schema, Map.of("equals", "shoes"));
      assertEquals(List.of(new QueryParameter("equals", "shoes")), result);
   }

   @Test
   void testFormObjectUnexploded() {
      // filter, form, explode=false -> filter=equals,shoes
      ObjectNode parameter = mapper.createObjectNode().put("name", "filter").put("style", "form").put("explode", false);
      ObjectNode schema = mapper.createObjectNode().put("type", "object");

      Map<String, Object> values = new LinkedHashMap<>();
      values.put("equals", "shoes");
      values.put("exists", "true");

      List<QueryParameter> result = serializer.serialize(parameter, schema, values);
      assertEquals(List.of(new QueryParameter("filter", "equals,shoes,exists,true")), result);
   }

   @Test
   void testSpaceDelimitedArray() {
      // tags, spaceDelimited, explode=false (default explode is false) -> tags=red blue
      ObjectNode parameter = mapper.createObjectNode().put("name", "tags").put("style", "spaceDelimited");
      ObjectNode schema = mapper.createObjectNode().put("type", "array");

      List<QueryParameter> result = serializer.serialize(parameter, schema, List.of("red", "blue"));
      assertEquals(List.of(new QueryParameter("tags", "red blue")), result);
   }

   @Test
   void testPipeDelimitedArray() {
      // tags, pipeDelimited, explode=false (default explode is false) -> tags=red|blue
      ObjectNode parameter = mapper.createObjectNode().put("name", "tags").put("style", "pipeDelimited");
      ObjectNode schema = mapper.createObjectNode().put("type", "array");

      List<QueryParameter> result = serializer.serialize(parameter, schema, List.of("red", "blue"));
      assertEquals(List.of(new QueryParameter("tags", "red|blue")), result);
   }

   @Test
   void testDeepObject() {
      // filter, deepObject, explode=true -> filter[equals]=shoes
      ObjectNode parameter = mapper.createObjectNode().put("name", "filter").put("style", "deepObject");
      ObjectNode schema = mapper.createObjectNode().put("type", "object");

      Map<String, Object> values = new LinkedHashMap<>();
      values.put("equals", "shoes");
      values.put("exists", true);

      List<QueryParameter> result = serializer.serialize(parameter, schema, values);
      assertEquals(2, result.size());
      assertEquals(new QueryParameter("filter[equals]", "shoes"), result.get(0));
      assertEquals(new QueryParameter("filter[exists]", "true"), result.get(1));
   }

   @Test
   void testPrimitiveParameter() {
      ObjectNode parameter = mapper.createObjectNode().put("name", "query").put("style", "form");
      ObjectNode schema = mapper.createObjectNode().put("type", "string");

      List<QueryParameter> result = serializer.serialize(parameter, schema, "sample");
      assertEquals(List.of(new QueryParameter("query", "sample")), result);
   }

   @Test
   void testNullValueReturnsEmpty() {
      ObjectNode parameter = mapper.createObjectNode().put("name", "query");
      List<QueryParameter> result = serializer.serialize(parameter, null, null);
      assertTrue(result.isEmpty());
   }
}
