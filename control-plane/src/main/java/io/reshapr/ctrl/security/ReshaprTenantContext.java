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
package io.reshapr.ctrl.security;

/**
 * ReshaprTenantContext is a utility class that provides a thread-local storage for the current tenant ID.
 * @author laurent
 */
public class ReshaprTenantContext {

   private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

   private ReshaprTenantContext() {
      // Private constructor for utility class
   }

   public static String getCurrentTenant() {
      return CURRENT_TENANT.get();
   }

   public static void setCurrentTenant(String tenant) {
      CURRENT_TENANT.set(tenant);
   }

   /**
    * Clear the current tenant context from the thread-local storage.
    */
   public static void clear() {
      CURRENT_TENANT.remove();
   }
}
