/*
 * Copyright The Microcks Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.reshapr.proxy.util;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Helper class for building URIs from various objects.
 * @author laurent
 */
public class URIBuilder {

   private URIBuilder() {
      // Hide default no argument constructor as it's a utility class.
   }

   /**
    * Build a URI from a URI pattern (using {} or /: for marked variable parts) and using other query parameters
    * @param pattern    The URI pattern to use
    * @param parameters The map of parameters K/V (whether template or query based)
    * @return The instanciated URI from template and parameters
    */
   public static String buildURIFromPattern(String pattern, Map<String, String> parameters) {
      if (parameters != null) {
         Multimap<String, String> multimap = parameters.entrySet().stream().collect(ArrayListMultimap::create,
               (m, e) -> m.put(e.getKey(), e.getValue()), Multimap::putAll);
         return buildURIFromPattern(pattern, multimap);
      }
      return pattern;
   }

   /**
    * Build a URI from a URI pattern (using {} or /: for marked variable parts) and using other query parameters
    * @param pattern    The URI pattern to use
    * @param parameters The Multimap of parameters K/V (whether template or query based)
    * @return The instanciated URI from template and parameters
    */
   public static String buildURIFromPattern(String pattern, Multimap<String, String> parameters) {
      if (parameters != null) {
         // Browse parameters and choose from template of query one.
         for (String parameterName : parameters.keySet()) {
            String wadltemplate = "{" + parameterName + "}";
            String swaggerTemplate = "/:" + parameterName;

            for (String parameterValue : parameters.get(parameterName)) {

               if (pattern.contains(wadltemplate)) {
                  // It's a template parameter.
                  pattern = pattern.replace(wadltemplate, encodePath(parameterValue));
               } else if (pattern.contains(swaggerTemplate)) {
                  // It's a template parameter.
                  pattern = pattern.replace(":" + parameterName, encodePath(parameterValue));
               } else {
                  // It's a query parameter, ensure we have started delimiting them.
                  if (!pattern.contains("?")) {
                     pattern += "?";
                  }
                  if (pattern.contains("=")) {
                     pattern += "&";
                  }
                  pattern += URLEncoder.encode(parameterName, StandardCharsets.UTF_8) + "=" + encodeValue(parameterValue);
               }
            }
         }
      }
      return pattern;
   }

   /** Utility method for getting URL encoding of query parameter. */
   private static String encodeValue(String value) {
      return URLEncoder.encode(value, StandardCharsets.UTF_8);
   }

   /**
    * Utility method for getting URL encoding of path parameter. We cannot use JDK method that only deal with query
    * parameters value. See https://stackoverflow.com/a/2678602 and https://www.baeldung.com/java-url-encoding-decoding.
    */
   private static String encodePath(String path) {
      return encodeUriComponent(path, StandardCharsets.UTF_8);
   }

   /** Duplicated method to encode a URI component using a specific charset from org.springframework.web.util.UriUtils.encodePath() */
   private static String encodeUriComponent(String source, Charset charset) {
      if (source == null || source.isEmpty()) {
         return source;
      }

      byte[] bytes = source.getBytes(charset);
      boolean original = true;
      for (byte b : bytes) {
         if (!isAllowed(b)) {
            original = false;
            break;
         }
      }
      if (original) {
         return source;
      }

      ByteArrayOutputStream baos = new ByteArrayOutputStream(bytes.length);
      for (byte b : bytes) {
         if (isAllowed(b)) {
            baos.write(b);
         }
         else {
            baos.write('%');
            char hex1 = Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16));
            char hex2 = Character.toUpperCase(Character.forDigit(b & 0xF, 16));
            baos.write(hex1);
            baos.write(hex2);
         }
      }
      return  baos.toString(charset);
   }

   private static boolean isAllowed(int c) {
      return isPchar(c) || '/' == c;
   }

   /**
    * Indicates whether the given character is in the {@code pchar} set.
    * @see <a href="https://www.ietf.org/rfc/rfc3986.txt">RFC 3986, appendix A</a>
    */
   private static boolean isPchar(int c) {
      return (isUnreserved(c) || isSubDelimiter(c) || ':' == c || '@' == c);
   }

   /**
    * Indicates whether the given character is in the {@code unreserved} set.
    * @see <a href="https://www.ietf.org/rfc/rfc3986.txt">RFC 3986, appendix A</a>
    */
   private static boolean isUnreserved(int c) {
      return (isAlpha(c) || isDigit(c) || '-' == c || '.' == c || '_' == c || '~' == c);
   }

   /**
    * Indicates whether the given character is in the {@code ALPHA} set.
    * @see <a href="https://www.ietf.org/rfc/rfc3986.txt">RFC 3986, appendix A</a>
    */
   private static boolean isAlpha(int c) {
      return (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z');
   }

   /**
    * Indicates whether the given character is in the {@code DIGIT} set.
    * @see <a href="https://www.ietf.org/rfc/rfc3986.txt">RFC 3986, appendix A</a>
    */
   private static boolean isDigit(int c) {
      return (c >= '0' && c <= '9');
   }

   /**
    * Indicates whether the given character is in the {@code sub-delims} set.
    * @see <a href="https://www.ietf.org/rfc/rfc3986.txt">RFC 3986, appendix A</a>
    */
   private static boolean isSubDelimiter(int c) {
      return ('!' == c || '$' == c || '&' == c || '\'' == c || '(' == c || ')' == c || '*' == c || '+' == c ||
            ',' == c || ';' == c || '=' == c);
   }
}
