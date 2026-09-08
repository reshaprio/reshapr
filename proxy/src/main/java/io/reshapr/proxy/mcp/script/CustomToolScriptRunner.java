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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.context.Context;
import io.roastedroot.quickjs4j.core.Engine;
import io.roastedroot.quickjs4j.core.Runner;
import jakarta.annotation.Nullable;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Assembles and runs a custom-tool JavaScript script using the QuickJS4J engine.
 * <p>
 * The user script is wrapped with:
 * <ul>
 *   <li>the {@code rs} façade prelude (ergonomic overloads over the low-level {@code __rs}
 *       builtins, plus {@code JSON.parse} of results);</li>
 *   <li>an {@code input} constant holding the tool call arguments;</li>
 *   <li>a {@code process()} guest function returning the {@code JSON.stringify} of the script's
 *       return value.</li>
 * </ul>
 * @author laurent
 */
public class CustomToolScriptRunner {

   /** Get a JBoss logging logger. */
   private static final Logger logger = Logger.getLogger(CustomToolScriptRunner.class);

   /** Executor spawning a fresh virtual thread per timed script execution (Futures are interruptible). */
   private static final ExecutorService SCRIPT_EXECUTOR =
         Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("rs-script-", 0).factory());

   /** Markers framing the structured payload of an {@code rs.fail(...)} error in a thrown JS message. */
   static final String RS_FAIL_MARKER = "__RS_FAIL__";
   static final String RS_END_MARKER = "__RS_END__";

   /** Prefix of the quickjs4j error message raised when the assembled script fails to compile. */
   static final String COMPILE_ERROR_PREFIX = "Failed to compile JS code:";

   /** The {@code rs} façade exposed to scripts, built on top of the {@code __rs} builtins. */
   private static final String RS_PRELUDE = """
         const rs = {
           callTool: function(a, b, c) {
             return JSON.parse(c === undefined ? __rs.callTool('', a, b) : __rs.callTool(a, b, c));
           },
           callToolAsync: function(a, b, c) {
             return c === undefined ? __rs.callToolAsync('', a, b) : __rs.callToolAsync(a, b, c);
           },
           awaitPromises: function(promises) {
             return __rs.awaitPromises(promises).map(function(s) { return JSON.parse(s); });
           },
           fail: function(message, data) {
             throw new Error('__RS_FAIL__' + JSON.stringify({ message: String(message), data: (data === undefined ? null : data) }) + '__RS_END__');
           }
         };
         """;

   private final ObjectMapper mapper;
   private final long timeoutMillis;

   /**
    * Build a CustomToolScriptRunner without an execution timeout.
    * @param mapper The object mapper used to serialize the script input arguments.
    */
   public CustomToolScriptRunner(ObjectMapper mapper) {
      this(mapper, 0L);
   }

   /**
    * Build a CustomToolScriptRunner.
    * @param mapper The object mapper used to serialize the script input arguments.
    * @param timeoutMillis The maximum script execution time in milliseconds; {@code <= 0} disables the timeout.
    */
   public CustomToolScriptRunner(ObjectMapper mapper, long timeoutMillis) {
      this.mapper = mapper;
      this.timeoutMillis = timeoutMillis;
   }

   /**
    * Run the given user script with the provided input arguments and host builtins, at script
    * nesting depth 1 (top-level).
    * @param userScript The raw JavaScript body provided in the custom tool {@code script}.
    * @param input The tool call arguments exposed to the script as the {@code input} constant.
    * @param builtins The {@code rs} host functions bridge bound to the script execution context.
    * @return The JSON string returned by the script (the {@code JSON.stringify} of its return value).
    */
   public String run(String userScript, Map<String, Object> input, ReshaprToolsBuiltins builtins) {
      return run(userScript, input, builtins, 1);
   }

   /**
    * Run the given user script with the provided input arguments and host builtins, at the given
    * script nesting depth.
    * @param userScript The raw JavaScript body provided in the custom tool {@code script}.
    * @param input The tool call arguments exposed to the script as the {@code input} constant.
    * @param builtins The {@code rs} host functions bridge bound to the script execution context.
    * @param depth The script nesting depth this execution runs at.
    * @return The JSON string returned by the script (the {@code JSON.stringify} of its return value).
    */
   public String run(String userScript, Map<String, Object> input, ReshaprToolsBuiltins builtins, int depth) {
      String fullScript = buildFullScript(userScript, input);

      Engine engine = Engine.builder()
            .addInvokables(ScriptApi_Invokables.toInvokables())
            .addBuiltins(ReshaprToolsBuiltins_Builtins.toBuiltins(builtins))
            .build();

      try (Runner runner = Runner.builder().withEngine(engine).build()) {
         ScriptApi api = ScriptApi_Invokables.create(fullScript, runner);

         // Capture the request scoped values and OTel context so they (and the script depth) are
         // re-established on whichever thread actually runs the script.
         MethodHandlingInfo capturedInfo = MethodHandlingContext.METHOD_HANDLING_INFO.isBound()
               ? MethodHandlingContext.METHOD_HANDLING_INFO.get() : null;
         Context capturedContext = Context.current();
         Callable<String> task = () -> runScoped(api, capturedInfo, capturedContext, depth);

         if (timeoutMillis <= 0) {
            return task.call();
         }
         return runWithTimeout(task);
      } catch (CustomToolScriptException e) {
         throw e;
      } catch (Exception e) {
         throw toScriptException(e);
      }
   }

   /** Run the guest {@code process()} with the captured context and script depth re-established. */
   private String runScoped(ScriptApi api, @Nullable MethodHandlingInfo info, Context otelContext, int depth)
         throws Exception {
      try (var ignored = otelContext.makeCurrent()) {
         ScopedValue.Carrier carrier = ScopedValue.where(ScriptExecutionContext.DEPTH, depth);
         if (info != null) {
            carrier = carrier.where(MethodHandlingContext.METHOD_HANDLING_INFO, info);
         }
         return carrier.call(api::process);
      }
   }

   /** Run the task on a virtual thread, enforcing the configured timeout. */
   private String runWithTimeout(Callable<String> task) {
      Future<String> future = SCRIPT_EXECUTOR.submit(task);
      try {
         return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
         // Best-effort cancellation: interruptible blocking (sleep, I/O) is cancelled; a pure
         // CPU loop in the JS engine cannot be interrupted and the orphan thread will finish later.
         future.cancel(true);
         throw new CustomToolScriptException("Custom tool script timed out",
               "Custom tool script timed out after " + timeoutMillis + " ms", e);
      } catch (ExecutionException e) {
         Throwable cause = e.getCause() != null ? e.getCause() : e;
         throw toScriptException(cause);
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         throw new CustomToolScriptException("Interrupted while running custom tool script",
               "Interrupted while running custom tool script", e);
      }
   }

   /**
    * Build a {@link CustomToolScriptException} from a raw failure cause, distinguishing script
    * compilation failures (whose quickjs4j message otherwise dumps the whole assembled script) from
    * runtime failures so that the former surface a concise, meaningful error.
    */
   private CustomToolScriptException toScriptException(Throwable cause) {
      String compileMessage = compilationMessage(cause);
      if (compileMessage != null) {
         String detail = extractCompilationDetail(compileMessage);
         logger.errorf("Custom tool script failed to compile: %s", detail);
         return new CustomToolScriptException("Custom tool script failed to compile", detail, cause, true);
      }
      logger.error("Exception while executing custom tool script", cause);
      return new CustomToolScriptException("Custom tool script execution failed",
            toErrorContent(cause.getMessage()), cause);
   }

   /** Walk the cause chain looking for the quickjs4j compilation error message, or {@code null}. */
   @Nullable
   private static String compilationMessage(Throwable cause) {
      for (Throwable current = cause; current != null; current = current.getCause()) {
         String message = current.getMessage();
         if (message != null && message.startsWith(COMPILE_ERROR_PREFIX)) {
            return message;
         }
      }
      return null;
   }

   /**
    * Extract the concise, user-meaningful part of a quickjs4j compilation error. The raw message
    * dumps the entire assembled script between the {@value #COMPILE_ERROR_PREFIX} prefix and a
    * trailing {@code "stderr: <syntax error>"} / {@code "stdout: ..."}; only the {@code stderr}
    * portion (the actual QuickJS syntax error) is retained — the full code dump is stripped.
    */
   String extractCompilationDetail(@Nullable String rawMessage) {
      if (rawMessage == null || rawMessage.isBlank()) {
         return "script compilation failed";
      }
      int stderrIdx = rawMessage.indexOf("\nstderr: ");
      if (stderrIdx >= 0) {
         String detail = rawMessage.substring(stderrIdx + "\nstderr: ".length());
         int stdoutIdx = detail.indexOf("\nstdout: ");
         if (stdoutIdx >= 0) {
            detail = detail.substring(0, stdoutIdx);
         }
         detail = detail.strip();
         if (!detail.isBlank()) {
            return sanitize(detail);
         }
      }
      return "script compilation failed";
   }

   /**
    * Build the MCP-facing error content from a thrown script message. A structured
    * {@code rs.fail(message, data)} payload is returned as canonical JSON; any other message is
    * surfaced as sanitized text.
    */
   String toErrorContent(@Nullable String rawMessage) {
      if (rawMessage == null || rawMessage.isBlank()) {
         return "Custom tool script failed";
      }
      int start = rawMessage.indexOf(RS_FAIL_MARKER);
      if (start >= 0) {
         int from = start + RS_FAIL_MARKER.length();
         int end = rawMessage.indexOf(RS_END_MARKER, from);
         if (end > from) {
            String json = rawMessage.substring(from, end);
            try {
               return mapper.writeValueAsString(mapper.readTree(json));
            } catch (Exception ignored) {
               // Not parseable, fall back to text.
            }
         }
      }
      return sanitize(rawMessage);
   }

   /** Strip a leading {@code "Error: "} prefix and truncate overly long messages. */
   private static String sanitize(String message) {
      String sanitized = message.strip();
      if (sanitized.startsWith("Error: ")) {
         sanitized = sanitized.substring("Error: ".length());
      }
      if (sanitized.length() > 2000) {
         sanitized = sanitized.substring(0, 2000) + "…";
      }
      return sanitized;
   }

   /** Build the complete script: prelude + input constant + wrapped process() function. */
   String buildFullScript(String userScript, Map<String, Object> input) {
      String inputJson;
      try {
         inputJson = mapper.writeValueAsString(input != null ? input : Map.of());
      } catch (Exception e) {
         inputJson = "{}";
      }

      return RS_PRELUDE
            + "const input = " + inputJson + ";\n"
            + "function __process() {\n" + userScript + "\n}\n"
            + "function process() { return JSON.stringify(__process()); }\n";
   }

   /** Thrown when a custom tool script fails to execute. */
   public static class CustomToolScriptException extends RuntimeException {

      /** The MCP-facing error content (sanitized text, or canonical JSON for {@code rs.fail}). */
      private final transient String errorContent;

      /** Whether this failure originates from the script failing to compile (vs. a runtime error). */
      private final boolean compilationError;

      /**
       * Build the exception with a distinct internal message and MCP-facing error content.
       * @param message The internal/log message.
       * @param errorContent The content surfaced to the MCP client (with {@code isError = true}).
       * @param cause The underlying cause.
       */
      public CustomToolScriptException(String message, String errorContent, Throwable cause) {
         this(message, errorContent, cause, false);
      }

      /**
       * Build the exception with a distinct internal message and MCP-facing error content.
       * @param message The internal/log message.
       * @param errorContent The content surfaced to the MCP client (with {@code isError = true}).
       * @param cause The underlying cause.
       * @param compilationError Whether the failure is a script compilation error.
       */
      public CustomToolScriptException(String message, String errorContent, Throwable cause, boolean compilationError) {
         super(message, cause);
         this.errorContent = errorContent;
         this.compilationError = compilationError;
      }

      public CustomToolScriptException(String message, Throwable cause) {
         this(message, message, cause, false);
      }

      /** The content to surface to the MCP client as an error result. */
      public String errorContent() {
         return errorContent;
      }

      /** Whether this failure is a script compilation error (vs. a runtime error). */
      public boolean isCompilationError() {
         return compilationError;
      }
   }
}

