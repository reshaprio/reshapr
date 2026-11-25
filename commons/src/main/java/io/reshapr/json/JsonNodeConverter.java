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
package io.reshapr.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;

/**
 * Utility methods for converting to Jackson JsonNode.
 * @author laurent
 */
public class JsonNodeConverter {

   private JsonNodeConverter() {
      // Private constructor to hide the implicit one as it's a utility class.
   }

   /**
    * Use this utility method to parse an unknonw text document (JSON or YAML) into a Jackson JsonNode.
    * @param textDocument the text document to parse
    * @return the parsed JsonNode
    * @throws IOException if the text document is not valid JSON or YAML
    */
   public static JsonNode getJsonNode(String textDocument) throws IOException {
      boolean isYaml = true;

      // Analyse first lines of content to guess content format.
      String line = null;
      int lineNumber = 0;
      BufferedReader reader = new BufferedReader(new StringReader(textDocument));
      while ((line = reader.readLine()) != null) {
         line = line.trim();
         // Check is we start with json object or array definition.
         if (lineNumber == 0 && (line.startsWith("{") || line.startsWith("["))) {
            isYaml = false;
            break;
         }
         if (line.startsWith("---") || line.startsWith("-") || line.startsWith("openapi: ")) {
            break;
         }
         lineNumber++;
      }
      reader.close();

      // Convert them to Node using Jackson object mapper.
      ObjectMapper mapper = isYaml ? ObjectMapperFactory.getYamlObjectMapper() : ObjectMapperFactory.getJsonObjectMapper();
      return mapper.readTree(textDocument);
   }
}
