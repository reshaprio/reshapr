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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.reshapr.json.JsonSchemaElements.JSON_SCHEMA_ARRAY_TYPE;
import static io.reshapr.json.JsonSchemaElements.JSON_SCHEMA_OBJECT_TYPE;
import static io.reshapr.json.JsonSchemaElements.JSON_SCHEMA_TYPE_ELEMENT;

/** Serializes OpenAPI {@code form} and {@code deepObject} query parameters. */
final class OpenApiQueryParameterSerializer {

   private final ObjectMapper mapper;

   OpenApiQueryParameterSerializer(ObjectMapper mapper) {
      this.mapper = mapper;
   }

   List<QueryParameter> serialize(JsonNode parameter, JsonNode parameterSchema, Object value) {
      String name = parameter.path("name").asText();
      String type = parameterSchema == null ? "" : parameterSchema.path(JSON_SCHEMA_TYPE_ELEMENT).asText();
      String style = parameter.path("style").asText("form");
      boolean explode = parameter.has("explode") ? parameter.path("explode").asBoolean() : "form".equals(style);

      if (JSON_SCHEMA_ARRAY_TYPE.equals(type)) {
         return serializeArray(name, mapper.convertValue(value, new TypeReference<List<Object>>() {}), explode);
      }
      if (JSON_SCHEMA_OBJECT_TYPE.equals(type)) {
         Map<String, Object> values = mapper.convertValue(value, new TypeReference<Map<String, Object>>() {});
         return "deepObject".equals(style) ? serializeDeepObject(name, values) : serializeFormObject(name, values, explode);
      }
      return List.of(new QueryParameter(name, value.toString()));
   }

   private List<QueryParameter> serializeArray(String name, List<Object> values, boolean explode) {
      if (!explode) {
         return List.of(new QueryParameter(name, String.join(",", values.stream().map(Object::toString).toList())));
      }
      return values.stream().map(value -> new QueryParameter(name, value.toString())).toList();
   }

   private List<QueryParameter> serializeDeepObject(String name, Map<String, Object> values) {
      return values.entrySet().stream()
            .map(entry -> new QueryParameter(name + "[" + entry.getKey() + "]", entry.getValue().toString()))
            .toList();
   }

   private List<QueryParameter> serializeFormObject(String name, Map<String, Object> values, boolean explode) {
      if (explode) {
         return values.entrySet().stream()
               .map(entry -> new QueryParameter(entry.getKey(), entry.getValue().toString()))
               .toList();
      }
      List<String> pairs = new ArrayList<>();
      values.forEach((key, value) -> {
         pairs.add(key);
         pairs.add(value.toString());
      });
      return List.of(new QueryParameter(name, String.join(",", pairs)));
   }

   record QueryParameter(String name, String value) {
   }
}
