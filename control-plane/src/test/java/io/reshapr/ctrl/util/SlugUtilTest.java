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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link SlugUtil}.
 * @author vaishnav
 */
class SlugUtilTest {

   @Test
   void testNullEmptyAndBlankInputs() {
      assertEquals("", SlugUtil.slugify((String[]) null));
      assertEquals("", SlugUtil.slugify());
      assertEquals("", SlugUtil.slugify((String) null));
      assertEquals("", SlugUtil.slugify("", "   ", "\t\n", null));
   }

   @Test
   void testMultiplePartsAndCaseNormalization() {
      assertEquals("petstore-api-1-0-0-default-plan",
            SlugUtil.slugify("Petstore API", "1.0.0", "Default Plan"));
      assertEquals("hello-world", SlugUtil.slugify("Hello", null, "  ", "World"));
   }

   @Test
   void testAccentsAndDiacriticsAreStripped() {
      assertEquals("cafe-creme-uber-resume",
            SlugUtil.slugify("Café Crème", "Über Résumé"));
   }

   @Test
   void testNonAlphanumericRunsAndLeadingTrailingDashes() {
      assertEquals("foo-bar-baz", SlugUtil.slugify("---foo___bar!!!baz---"));
      assertEquals("a-b", SlugUtil.slugify("  --a--  ", "  --b--  "));
      assertEquals("", SlugUtil.slugify("---", "!!!", "@@@"));
   }

   @Test
   void testAlreadyValidSlugUnchanged() {
      assertEquals("my-service-v2", SlugUtil.slugify("my-service-v2"));
   }
}

