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
package io.reshapr.proxy.mcp.script;

import io.reshapr.proxy.context.MethodHandlingContext;
import io.reshapr.proxy.context.MethodHandlingInfo;
import io.reshapr.proxy.mcp.DeclaredTool;
import io.reshapr.proxy.mcp.McpSchema;
import io.reshapr.proxy.mcp.ResponseAttributes;
import io.reshapr.proxy.mcp.ToolCallExecutor;
import io.reshapr.proxy.registry.ExpositionEntry;
import io.reshapr.proxy.registry.GatewayRegistry;
import io.reshapr.proxy.registry.ServiceEntry;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.opentelemetry.context.Context;
import io.roastedroot.quickjs4j.annotations.Builtins;
import io.roastedroot.quickjs4j.annotations.HostFunction;
import io.roastedroot.quickjs4j.annotations.HostRefParam;
import io.roastedroot.quickjs4j.annotations.ReturnsHostRef;
import jakarta.annotation.Nullable;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The {@code rs} host API exposed to custom-tool scripts. It bridges JavaScript calls to the
 * {@link ToolCallExecutor} so a script can invoke other tools of the same or another service.
 * <p>
 * The low-level builtins object is registered under the {@code __rs} name. Custom tool scripts
 * use the ergonomic {@code rs.callTool}/{@code rs.callToolAsync}/{@code rs.awaitPromises} façade
 * (defined in a JS prelude) which normalizes argument arity and parses JSON results.
 * <p>
 * Each host method receives a {@code serviceCoordinate} ({@code <service_name:service_version>}
 * or blank for the same service as the script). Blocking calls run on the script thread, hence
 * within the request {@link java.lang.ScopedValue} scope. Asynchronous calls capture the
 * {@link MethodHandlingInfo} and the OpenTelemetry {@link Context} and re-establish them on the
 * virtual thread that performs the call, ensuring scoped values and distributed traces are
 * propagated.
 * @author laurent
 */
@Builtins("__rs")
public final class ReshaprToolsBuiltins {

   /** Get a JBoss logging logger. */
   private static final Logger logger = Logger.getLogger(ReshaprToolsBuiltins.class);

   /** Default maximum number of tool calls allowed within a single script execution. */
   public static final int DEFAULT_MAX_TOOL_CALLS = 20;

   /** Executor spawning a fresh virtual thread per asynchronous tool call. */
   private static final Executor VT_EXECUTOR = command -> Thread.ofVirtual().name("rs-tool-async").start(command);

   private final ExpositionEntry currentExposition;
   private final GatewayRegistry gatewayRegistry;
   private final ToolCallExecutor toolCallExecutor;
   private final Map<String, List<String>> headers;
   private final List<DeclaredTool> allowedTools;
   private final int maxToolCalls;

   private final AtomicInteger callCounter = new AtomicInteger(0);
   private final ObjectMapper mapper = new ObjectMapper();

   /**
    * Aggregated response attributes across every tool call made by the script. Each successful
    * (or failing) sub-call contributes via {@link ResponseAttributes#merge(ResponseAttributes)},
    * applying the header-specific merge policy (e.g. MAX for upstream service time). The carrier
    * itself is thread-safe, which matters for asynchronous {@code callToolAsync}/{@code awaitPromises}.
    */
   private final ResponseAttributes accumulatedAttrs = ResponseAttributes.empty();

   /**
    * Build a ReshaprToolsBuiltins bound to a script execution context.
    * @param currentExposition The exposition the running script belongs to.
    * @param gatewayRegistry The registry used to resolve cross-service calls.
    * @param toolCallExecutor The executor performing the actual tool calls.
    * @param headers The protocol-level headers to propagate to tool calls.
    * @param allowedTools The allow-list declared in the script {@code tools} section.
    * @param maxToolCalls The maximum number of tool calls allowed within this script execution.
    */
   public ReshaprToolsBuiltins(ExpositionEntry currentExposition, GatewayRegistry gatewayRegistry,
                               ToolCallExecutor toolCallExecutor, Map<String, List<String>> headers,
                               List<DeclaredTool> allowedTools, int maxToolCalls) {
      this.currentExposition = currentExposition;
      this.gatewayRegistry = gatewayRegistry;
      this.toolCallExecutor = toolCallExecutor;
      this.headers = headers;
      this.allowedTools = allowedTools != null ? allowedTools : List.of();
      this.maxToolCalls = maxToolCalls > 0 ? maxToolCalls : DEFAULT_MAX_TOOL_CALLS;
   }

   /** Convenience constructor using {@link #DEFAULT_MAX_TOOL_CALLS}. */
   public ReshaprToolsBuiltins(ExpositionEntry currentExposition, GatewayRegistry gatewayRegistry,
                               ToolCallExecutor toolCallExecutor, Map<String, List<String>> headers,
                               List<DeclaredTool> allowedTools) {
      this(currentExposition, gatewayRegistry, toolCallExecutor, headers, allowedTools, DEFAULT_MAX_TOOL_CALLS);
   }

   /**
    * Synchronously call a tool. Runs on the script thread, within the request scoped values.
    * @param serviceCoordinate The {@code <service_name:service_version>} couple, or blank for the same service.
    * @param tool The name of the tool to call.
    * @param params The tool arguments (a JS object or its JSON representation).
    * @return The JSON-serialized result object {@code { ok, content, error }}.
    */
   @HostFunction
   public String callTool(String serviceCoordinate, String tool, Object params) {
      logger.debugf("callTool(%s, %s, %s)", serviceCoordinate, tool, params);
      return invokeAndSerialize(serviceCoordinate, tool, toArgumentsMap(params));
   }

   /**
    * Asynchronously call a tool. The request scoped values and OpenTelemetry context are captured
    * now and re-established on the virtual thread that performs the call.
    * @param serviceCoordinate The {@code <service_name:service_version>} couple, or blank for the same service.
    * @param tool The name of the tool to call.
    * @param params The tool arguments (a JS object or its JSON representation).
    * @return A host-ref promise wrapping the future JSON-serialized result.
    */
   @HostFunction
   @ReturnsHostRef
   public CallToolPromise callToolAsync(String serviceCoordinate, String tool, Object params) {
      logger.debugf("callToolAsync(%s, %s, %s)", serviceCoordinate, tool, params);
      Map<String, Object> args = toArgumentsMap(params);

      // Capture the scoped values and OTel context on the (in-scope) script thread.
      MethodHandlingInfo capturedInfo = MethodHandlingContext.METHOD_HANDLING_INFO.isBound()
            ? MethodHandlingContext.METHOD_HANDLING_INFO.get() : null;
      int capturedDepth = ScriptExecutionContext.currentDepth();
      Context capturedContext = Context.current();

      CompletableFuture<String> future = CompletableFuture.supplyAsync(
            () -> runWithCapturedContext(capturedInfo, capturedDepth, capturedContext, serviceCoordinate, tool, args),
            VT_EXECUTOR);
      return new CallToolPromise(future);
   }

   /**
    * Await the completion of the given asynchronous tool call promises.
    * @param promises The promises returned by {@link #callToolAsync}.
    * @return An array of JSON-serialized result objects, in the same order as the promises. A
    *         failed promise yields an error result rather than throwing, so the script can decide
    *         whether to propagate it.
    */
   @HostFunction
   public String[] awaitPromises(@HostRefParam CallToolPromise... promises) {
      logger.debugf("awaitPromises(%s)", Arrays.toString(promises));
      String[] results = new String[promises.length];
      for (int i = 0; i < promises.length; i++) {
         try {
            results[i] = promises[i].future.get();
            logger.debugf("Get promise #%d result", i);
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            results[i] = errorJson(McpSchema.ErrorCodes.INTERNAL_ERROR, "Interrupted while awaiting tool call");
         } catch (Exception e) {
            logger.warnf("Asynchronous tool call promise failed: %s", e.getMessage());
            results[i] = errorJson(McpSchema.ErrorCodes.INTERNAL_ERROR, e.getMessage());
         }
      }
      return results;
   }

   /** Re-establish the captured context then perform the tool call. */
   private String runWithCapturedContext(@Nullable MethodHandlingInfo info, int depth, Context otelContext,
                                         String serviceCoordinate, String tool, Map<String, Object> args) {
      try (var ignored = otelContext.makeCurrent()) {
         ScopedValue.Carrier carrier = ScopedValue.where(ScriptExecutionContext.DEPTH, depth);
         if (info != null) {
            carrier = carrier.where(MethodHandlingContext.METHOD_HANDLING_INFO, info);
         }
         return carrier.call(() -> invokeAndSerialize(serviceCoordinate, tool, args));
      } catch (Exception e) {
         logger.error("Exception during asynchronous tool call", e);
         return errorJson(McpSchema.ErrorCodes.INTERNAL_ERROR, e.getMessage());
      }
   }

   /** Enforce guard-rails and allow-list, resolve the target service, execute and serialize. */
   private String invokeAndSerialize(String serviceCoordinate, String tool, Map<String, Object> args) {
      // Guard-rail: maximum number of tool calls per script execution.
      if (callCounter.incrementAndGet() > maxToolCalls) {
         return errorJson(McpSchema.ErrorCodes.INVALID_REQUEST,
               "Maximum number of tool calls (" + maxToolCalls + ") exceeded for this script");
      }

      // Security: only tools declared in the script 'tools' allow-list may be called.
      boolean allowed = allowedTools.stream().anyMatch(a -> DeclaredTool.matches(a, serviceCoordinate, tool));
      if (!allowed) {
         String target = isSameService(serviceCoordinate) ? "'" + tool + "'"
               : "'" + tool + "' on service '" + serviceCoordinate + "'";
         return errorJson(McpSchema.ErrorCodes.INVALID_PARAMS,
               "Tool " + target + " is not declared in the script 'tools' allow-list");
      }

      // Resolve and execute: same-service stays on the current exposition (deterministic config/backend/
      // artifacts, even for a non-elected plan); cross-service resolves the target service's elected exposition.
      ToolCallExecutor.ToolCallOutcome outcome;
      if (isSameService(serviceCoordinate)) {
         outcome = toolCallExecutor.executeInternal(currentExposition, tool, args, headers);
      } else {
         ServiceEntry targetService = resolveService(serviceCoordinate);
         if (targetService == null) {
            return errorJson(McpSchema.ErrorCodes.INVALID_PARAMS,
                  "Unknown or unauthorized service '" + serviceCoordinate + "'");
         }
         outcome = toolCallExecutor.executeInternal(targetService, tool, args, headers);
      }
      return switch (outcome) {
         case ToolCallExecutor.Success success -> {
            accumulatedAttrs.merge(success.attrs());
            yield successJson(success.content(), success.isFault());
         }
         case ToolCallExecutor.Failure failure -> errorJson(failure.code(), failure.message());
         case ToolCallExecutor.ElicitationRequired ignored -> errorJson(McpSchema.ErrorCodes.URL_ELICITATION_REQUIRED,
               "Tool '" + tool + "' requires backend secret elicitation that was not resolved before running the script");
      };
   }

   /**
    * The response attributes accumulated across all tool calls made by the script. Used by
    * {@link io.reshapr.proxy.mcp.converters.ReshaprCustomToolsMcpToolConverter} to propagate
    * aggregated backend metadata (e.g. worst upstream service time) up to the MCP response.
    */
   public ResponseAttributes accumulatedAttrs() {
      return accumulatedAttrs;
   }

   /** Resolve the target service for a cross-service coordinate, restricted to the same organization. */
   @Nullable
   private ServiceEntry resolveService(String serviceCoordinate) {
      String[] parts = serviceCoordinate.split(":", 2);
      if (parts.length != 2) {
         return null;
      }
      // Cross-service resolution is scoped to the current organization.
      return gatewayRegistry.getService(currentExposition.service().organizationId(), parts[0], parts[1]);
   }

   private static boolean isSameService(@Nullable String serviceCoordinate) {
      return serviceCoordinate == null || serviceCoordinate.isBlank();
   }

   /** Convert the JS-provided params into a Java arguments map. */
   private Map<String, Object> toArgumentsMap(@Nullable Object params) {
      if (params == null) {
         return Map.of();
      }
      try {
         if (params instanceof String s) {
            return s.isBlank() ? Map.of() : mapper.readValue(s, new TypeReference<Map<String, Object>>() {});
         }
         return mapper.convertValue(params, new TypeReference<Map<String, Object>>() {});
      } catch (Exception e) {
         logger.warnf("Cannot convert script tool params to a map: %s", e.getMessage());
         return Map.of();
      }
   }

   /** Build the JSON result object for a successful call. */
   private String successJson(@Nullable String content, boolean isFault) {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("ok", !isFault);
      result.put("content", parseContent(content));
      result.put("error", null);
      return toJson(result);
   }

   /** Build the JSON result object for a failed call. */
   private String errorJson(int code, @Nullable String message) {
      Map<String, Object> error = new LinkedHashMap<>();
      error.put("code", code);
      error.put("message", message);
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("ok", false);
      result.put("content", null);
      result.put("error", error);
      return toJson(result);
   }

   /** Parse the tool response content as JSON when possible, else return it as a raw string. */
   @Nullable
   private Object parseContent(@Nullable String content) {
      if (content == null || content.isEmpty()) {
         return null;
      }
      try {
         return mapper.readValue(content, Object.class);
      } catch (Exception e) {
         return content;
      }
   }

   private String toJson(Object value) {
      try {
         return mapper.writeValueAsString(value);
      } catch (Exception e) {
         return "{\"ok\":false,\"content\":null,\"error\":{\"code\":" + McpSchema.ErrorCodes.INTERNAL_ERROR
               + ",\"message\":\"result serialization error\"}}";
      }
   }

   /**
    * Host reference wrapping the future result of an asynchronous tool call. Mirrors the pattern
    * expected by the QuickJS4J host-ref mechanism.
    */
   @JsonDeserialize(using = JsonDeserializer.None.class)
   @JsonInclude(JsonInclude.Include.NON_NULL)
   public static final class CallToolPromise {
      @JsonProperty("future")
      public final CompletableFuture<String> future;

      public CallToolPromise(CompletableFuture<String> future) {
         this.future = future;
      }
   }
}
