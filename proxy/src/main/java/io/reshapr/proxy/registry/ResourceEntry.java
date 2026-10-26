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
package io.reshapr.proxy.registry;

import java.util.Arrays;
import java.util.Objects;

/**
 * Represents a resource registry entry.
 * author @laurent
 */
public record ResourceEntry(
      String type,
      String resourceUri,
      String[] visibility) {

   @Override
   public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      ResourceEntry that = (ResourceEntry) o;
      return Objects.equals(type, that.type)
            && Objects.equals(resourceUri, that.resourceUri)
            && Arrays.equals(visibility, that.visibility);
   }

   @Override
   public int hashCode() {
      int result = Objects.hash(type, resourceUri);
      result = 31 * result + Arrays.hashCode(visibility);
      return result;
   }

   @Override
   public String toString() {
      return "ResourceEntry[" +
            "type=" + type +
            ", resourceUri=" + resourceUri +
            ", visibility=" + (visibility == null ? "null" : Arrays.toString(visibility)) +
            ']';
   }
}

