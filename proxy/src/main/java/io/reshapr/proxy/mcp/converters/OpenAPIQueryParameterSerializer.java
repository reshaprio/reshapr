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
import java.util.stream.Collectors;

import static io.reshapr.json.JsonSchemaElements.JSON_SCHEMA_ARRAY_TYPE;
import static io.reshapr.json.JsonSchemaElements.JSON_SCHEMA_OBJECT_TYPE;
import static io.reshapr.json.JsonSchemaElements.JSON_SCHEMA_TYPE_ELEMENT;

/**
 * Serializes OpenAPI 3.0 and 3.1 query parameters into HTTP query key-value pairs according to their
 * declared {@code style} and {@code explode} attributes.
 *
 * <p>Supported styles:
 * <ul>
 *   <li><b>form</b> (OAS default for query):
 *     <ul>
 *       <li>Primitive: {@code name=value}</li>
 *       <li>Array, {@code explode=true} (default): {@code name=val1&name=val2}</li>
 *       <li>Array, {@code explode=false}: {@code name=val1,val2}</li>
 *       <li>Object, {@code explode=true}: {@code key1=val1&key2=val2}</li>
 *       <li>Object, {@code explode=false}: {@code name=key1,val1,key2,val2}</li>
 *     </ul>
 *   </li>
 *   <li><b>spaceDelimited</b>:
 *     <ul>
 *       <li>Array, {@code explode=true}: {@code name=val1&name=val2}</li>
 *       <li>Array, {@code explode=false} (default): {@code name=val1 val2}</li>
 *       <li>Object, {@code explode=true}: {@code key1=val1&key2=val2}</li>
 *       <li>Object, {@code explode=false} (default): {@code name=key1 val1 key2 val2}</li>
 *     </ul>
 *   </li>
 *   <li><b>pipeDelimited</b>:
 *     <ul>
 *       <li>Array, {@code explode=true}: {@code name=val1&name=val2}</li>
 *       <li>Array, {@code explode=false} (default): {@code name=val1|val2}</li>
 *       <li>Object, {@code explode=true}: {@code key1=val1&key2=val2}</li>
 *       <li>Object, {@code explode=false} (default): {@code name=key1|val1|key2|val2}</li>
 *     </ul>
 *   </li>
 *   <li><b>deepObject</b>:
 *     <ul>
 *       <li>Object, {@code explode=true} (default/only defined): {@code name[key1]=val1&name[key2]=val2}</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * @see <a href="https://spec.openapis.org/oas/v3.1.1.html#style-values">OpenAPI Style Values</a>
 * @author vaishnav
 */
final class OpenAPIQueryParameterSerializer {

   private static final String STYLE_FORM = "form";
   private static final String STYLE_SPACE_DELIMITED = "spaceDelimited";
   private static final String STYLE_PIPE_DELIMITED = "pipeDelimited";
   private static final String STYLE_DEEP_OBJECT = "deepObject";

   private final ObjectMapper mapper;

   /**
    * Constructs a serializer using the specified Jackson {@link ObjectMapper}.
    *
    * @param mapper the mapper used for converting complex values
    */
   OpenAPIQueryParameterSerializer(ObjectMapper mapper) {
      this.mapper = mapper;
   }

   /**
    * Serializes an argument value for an OpenAPI query parameter into one or more {@link QueryParameter} pairs.
    *
    * @param parameter       the OpenAPI parameter definition node (containing style, explode, etc.)
    * @param parameterSchema the resolved schema node for the parameter (containing type, properties, etc.)
    * @param value           the parameter value supplied by the MCP tool invocation
    * @return the list of serialized query parameter name-value pairs
    */
   List<QueryParameter> serialize(JsonNode parameter, JsonNode parameterSchema, Object value) {
      if (value == null) {
         return List.of();
      }

      String name = parameter.path("name").asText();
      String type = parameterSchema == null ? "" : parameterSchema.path(JSON_SCHEMA_TYPE_ELEMENT).asText();
      String style = parameter.path("style").asText(STYLE_FORM);

      // In OpenAPI 3.0/3.1:
      // - 'form' defaults explode to true
      // - 'deepObject' defaults explode to true
      // - 'spaceDelimited' defaults explode to false
      // - 'pipeDelimited' defaults explode to false
      boolean defaultExplode = !STYLE_SPACE_DELIMITED.equals(style) && !STYLE_PIPE_DELIMITED.equals(style);
      boolean explode = parameter.has("explode") ? parameter.path("explode").asBoolean() : defaultExplode;
      String delimiter = resolveDelimiter(style);

      if (JSON_SCHEMA_ARRAY_TYPE.equals(type) || (type.isEmpty() && value instanceof List)) {
         List<Object> list = mapper.convertValue(value, new TypeReference<List<Object>>() {});
         return serializeArray(name, list, delimiter, explode);
      }

      if (JSON_SCHEMA_OBJECT_TYPE.equals(type) || (type.isEmpty() && value instanceof Map)) {
         Map<String, Object> values = mapper.convertValue(value, new TypeReference<Map<String, Object>>() {});
         if (STYLE_DEEP_OBJECT.equals(style)) {
            return serializeDeepObject(name, values);
         }
         return serializeObject(name, values, delimiter, explode);
      }

      // Default scalar / primitive serialization
      return List.of(new QueryParameter(name, value.toString()));
   }

   private String resolveDelimiter(String style) {
      return switch (style) {
         case STYLE_SPACE_DELIMITED -> " ";
         case STYLE_PIPE_DELIMITED -> "|";
         default -> ",";
      };
   }

   private List<QueryParameter> serializeArray(String name, List<Object> values, String delimiter, boolean explode) {
      if (values == null || values.isEmpty()) {
         return List.of();
      }
      if (!explode) {
         return List.of(new QueryParameter(name, values.stream().map(Object::toString).collect(Collectors.joining(delimiter))));
      }
      return values.stream().map(value -> new QueryParameter(name, value.toString())).toList();
   }

   private List<QueryParameter> serializeDeepObject(String name, Map<String, Object> values) {
      if (values == null || values.isEmpty()) {
         return List.of();
      }
      return values.entrySet().stream()
            .map(entry -> new QueryParameter(name + "[" + entry.getKey() + "]", entry.getValue().toString()))
            .toList();
   }

   private List<QueryParameter> serializeObject(String name, Map<String, Object> values, String delimiter, boolean explode) {
      if (values == null || values.isEmpty()) {
         return List.of();
      }
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
      return List.of(new QueryParameter(name, String.join(delimiter, pairs)));
   }

   /**
    * Represents a single serialized HTTP query parameter key-value pair.
    *
    * @param name  the parameter name (e.g. {@code tags} or {@code filter[equals]})
    * @param value the serialized parameter value
    */
   record QueryParameter(String name, String value) {
   }
}
