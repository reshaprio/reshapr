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

/**
 * Raised when a declarative CustomTool cannot be resolved to a valid target operation, typically because it
 * references a tool that does not exist within the current service. This is a configuration error that must be
 * surfaced to the caller as a clean MCP error rather than an unhandled exception.
 * @author laurent
 */
public class CustomToolResolutionException extends RuntimeException {

   /**
    * Build the exception for a custom tool referencing an unknown target tool.
    * @param customToolName The name of the declarative custom tool being invoked.
    * @param targetToolName The name of the referenced (and missing) target tool.
    */
   public CustomToolResolutionException(String customToolName, String targetToolName) {
      super("Custom tool '" + customToolName + "' references unknown tool '" + targetToolName
            + "' which does not exist in the current service");
   }
}
