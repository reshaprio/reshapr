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
package io.reshapr.ctrl.util;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Small helper to build URL/DNS-friendly slugs (lowercase, ascii, dash-separated). Used notably to
 * propose a default {@code name} for an Exposition from the triple service-name + service-version +
 * configuration-plan-name.
 * @author laurent
 */
public final class SlugUtil {

   private SlugUtil() {
      // Utility class, no instantiation.
   }

   /**
    * Slugify the given parts into a single lowercase, dash-separated ascii slug. Accents are stripped,
    * any run of non-alphanumeric characters is collapsed to a single dash and leading/trailing dashes
    * are trimmed.
    * @param parts The textual parts to join and slugify (nulls and blanks are ignored).
    * @return A slug, or an empty string if no usable input was provided.
    */
   public static String slugify(String... parts) {
      if (parts == null || parts.length == 0) {
         return "";
      }
      StringBuilder joined = new StringBuilder();
      for (String part : parts) {
         if (part != null && !part.isBlank()) {
            if (!joined.isEmpty()) {
               joined.append('-');
            }
            joined.append(part);
         }
      }
      if (joined.isEmpty()) {
         return "";
      }
      String normalized = Normalizer.normalize(joined.toString(), Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "");
      String collapsed = normalized.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-");
      int start = 0;
      int end = collapsed.length();
      while (start < end && collapsed.charAt(start) == '-') {
         start++;
      }
      while (end > start && collapsed.charAt(end - 1) == '-') {
         end--;
      }
      return collapsed.substring(start, end);
   }
}


