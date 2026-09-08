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
package io.reshapr.proxy.mcp;

import io.reshapr.proxy.context.MethodHandlingContext;
import io.reshapr.proxy.context.SessionInfo;
import io.reshapr.proxy.mcp.converters.CustomToolResolutionException;
import io.reshapr.proxy.mcp.converters.GraphQLMcpToolConverter;
import io.reshapr.proxy.mcp.converters.GrpcMcpToolConverter;
import io.reshapr.proxy.mcp.converters.McpToolConverter;
import io.reshapr.proxy.mcp.converters.OpenAPIMcpToolConverter;
import io.reshapr.proxy.mcp.converters.ReshaprCustomToolsMcpToolConverter;
import io.reshapr.proxy.mcp.filters.ToolsOutputFiltersApplier;
import io.reshapr.proxy.mcp.state.ElicitationStore;
import io.reshapr.proxy.mcp.state.UserSecretStore;
import io.reshapr.proxy.proxy.GrpcProxyService;
import io.reshapr.proxy.proxy.ProxyService;
import io.reshapr.proxy.registry.ArtifactEntry;
import io.reshapr.proxy.registry.ArtifactEntryType;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.ExpositionEntry;
import io.reshapr.proxy.registry.GatewayRegistry;
import io.reshapr.proxy.registry.OperationEntry;
import io.reshapr.proxy.registry.SecretEntry;
import io.reshapr.proxy.registry.ServiceEntry;
import io.reshapr.proxy.util.WebUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Centralizes the execution of an MCP tool call for a given service: backend secret /
 * elicitation handling, MCP tool converter selection, operation resolution, backend invocation
 * and output filtering. It is reused both by the {@link McpController} (to serve tools/call and
 * tools/list requests) and by custom-tool scripts that need to invoke other tools.
 * @author laurent
 */
@ApplicationScoped
public class ToolCallExecutor {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final GatewayRegistry gatewayRegistry;
   private final ElicitationStore elicitationStore;
   private final UserSecretStore userSecretStore;
   private final WorkCache workCache;
   private final ProxyService proxyService;
   private final GrpcProxyService grpcProxyService;

   private final ObjectMapper mapper = new ObjectMapper();

   @ConfigProperty(name = "reshapr.gateway.fqdns", defaultValue = "localhost:7777")
   List<String> fqdns;

   @ConfigProperty(name = "reshapr.gateway.scripting.timeout", defaultValue = "10000")
   long scriptTimeoutMillis;

   @ConfigProperty(name = "reshapr.gateway.scripting.max-tool-calls", defaultValue = "10")
   int scriptMaxToolCalls;

   @ConfigProperty(name = "reshapr.gateway.scripting.max-depth", defaultValue = "5")
   int scriptMaxDepth;

   /** The maximum custom-tool script execution time in milliseconds ({@code <= 0} disables it). */
   public long scriptTimeoutMillis() {
      return scriptTimeoutMillis;
   }

   /** The maximum number of tool calls allowed within a single script execution. */
   public int scriptMaxToolCalls() {
      return scriptMaxToolCalls;
   }

   /** The maximum custom-tool script nesting depth (anti cross-script recursion). */
   public int scriptMaxDepth() {
      return scriptMaxDepth;
   }

   /**
    * Build a ToolCallExecutor with required dependencies.
    * @param gatewayRegistry The registry to access services and configurations.
    * @param elicitationStore The store for managing elicitation flows.
    * @param userSecretStore The store for per-user elicited secrets (stateless mode).
    * @param workCache The work cache for temporary data storage.
    * @param proxyService The proxy service for handling HTTP proxying.
    * @param grpcProxyService The gRPC proxy service for handling gRPC proxying.
    */
   public ToolCallExecutor(GatewayRegistry gatewayRegistry, ElicitationStore elicitationStore,
                           UserSecretStore userSecretStore, WorkCache workCache,
                           ProxyService proxyService, GrpcProxyService grpcProxyService) {
      this.gatewayRegistry = gatewayRegistry;
      this.elicitationStore = elicitationStore;
      this.userSecretStore = userSecretStore;
      this.workCache = workCache;
      this.proxyService = proxyService;
      this.grpcProxyService = grpcProxyService;
   }

   /**
    * The outcome of a tool call execution. It is either a successful response, an elicitation
    * requirement (the caller must collect backend secrets before retrying), or a failure.
    */
   public sealed interface ToolCallOutcome permits Success, ElicitationRequired, Failure {
   }

   /** A successful tool call holding the (possibly filtered) response content and the response attributes. */
   public record Success(String content, boolean isFault, ResponseAttributes attrs) implements ToolCallOutcome {
      /** Backward-compatible constructor building a success with no attributes. */
      public Success(String content, boolean isFault) {
         this(content, isFault, ResponseAttributes.empty());
      }
   }

   /**
    * The call requires one or more backend secrets to be elicited first. In stateless mode it also carries
    * the opaque {@code requestState} the client must return to resume the paused request (URL Mode OAuth);
    * it is {@code null} in legacy (session-bound) mode.
    */
   public record ElicitationRequired(List<McpSchema.URLElicitation> elicitations,
                                     @Nullable String requestState) implements ToolCallOutcome {

      /** Legacy (session-bound) elicitation requirement, without a {@code requestState}. */
      public ElicitationRequired(List<McpSchema.URLElicitation> elicitations) {
         this(elicitations, null);
      }
   }

   /** The call failed with a JSON-RPC style error code and message. */
   public record Failure(int code, String message, @Nullable Object data) implements ToolCallOutcome {
   }

   /**
    * Execute a tool call on the given exposition (deterministic path: the exposition carries its own
    * configuration and artifacts, so two configuration plans of the same service never collide).
    * @param exposition The exposition exposing the tool.
    * @param toolName The name of the tool to call.
    * @param arguments The tool arguments.
    * @param headers The protocol-level headers to propagate (a mutable copy is recommended).
    * @return The {@link ToolCallOutcome} of the execution.
    */
   @WithSpan
   public ToolCallOutcome execute(ExpositionEntry exposition, @SpanAttribute("mcp.target.name") String toolName,
                                  Map<String, Object> arguments, Map<String, List<String>> headers) {
      ServiceEntry service = exposition.service();
      // Selectively complete span attributes because we don't want to have the full ServiceEntry added.
      Span.current().setAttribute("service.name", service.name());
      Span.current().setAttribute("service.version", service.version());

      ConfigurationEntry configuration = exposition.configuration();

      // Check whether the backend secret requires elicitation before proceeding.
      ToolCallOutcome elicitationOutcome = checkBackendSecretElicitation(service, configuration);
      if (elicitationOutcome != null) {
         return elicitationOutcome;
      }

      // Build converter based on service type and resolve the target operation.
      McpToolConverter converter = buildMcpToolConverter(exposition);

      OperationEntry callOperation = converter.getAvailableOperations(service).stream()
            .filter(operation -> isExposedOperation(configuration, operation))
            .filter(operation -> toolName.equals(converter.getToolName(operation)))
            .findFirst().orElse(null);
      if (callOperation == null) {
         return new Failure(McpSchema.ErrorCodes.INVALID_PARAMS, "Unknown tool: " + toolName, null);
      }

      // If the resolved operation declares a set of tools it may call (e.g. a script-based custom
      // tool), run the elicitation pre-flight on those declared tools before invoking the operation.
      List<DeclaredTool> declaredTools = converter.getDeclaredTools(callOperation);
      if (declaredTools != null) {
         ToolCallOutcome preflight = preflightToolsElicitation(exposition, declaredTools);
         if (preflight != null) {
            return preflight;
         }
      }

      // We copy headers before calling because the original map may be immutable.
      McpSchema.SimpleRequest toolRequest = new McpSchema.SimpleRequest(toolName, arguments);
      McpToolConverter.Response response;
      try {
         response = converter.getCallResponse(callOperation, configuration, toolRequest, new HashMap<>(headers));
      } catch (CustomToolResolutionException e) {
         // A declarative custom tool referenced a non-existent target tool: surface a clean MCP error
         // instead of letting the underlying NullPointerException escape as a non-JSON-RPC 500 response.
         logger.warnf("Cannot resolve custom tool '%s' on service '%s': %s", toolName, service.name(), e.getMessage());
         return new Failure(McpSchema.ErrorCodes.INTERNAL_ERROR, e.getMessage(), null);
      }

      String content = response.content();

      // Apply output filters if a ToolsOutputFilters artifact is attached.
      ToolsOutputFiltersApplier filterApplier = buildToolsOutputFilterApplier(exposition);
      if (filterApplier != null) {
         content = filterApplier.applyFilter(toolName, content);
      }

      return new Success(content, response.isFault(), response.attrs());
   }

   /**
    * Execute a tool call on the given service, resolving its elected exposition (last configuration plan).
    * This convenience overload is used by cross-service script calls and legacy callers that only hold a
    * {@link ServiceEntry}; the deterministic path is {@link #execute(ExpositionEntry, String, Map, Map)}.
    * @param service The service exposing the tool.
    * @param toolName The name of the tool to call.
    * @param arguments The tool arguments.
    * @param headers The protocol-level headers to propagate (a mutable copy is recommended).
    * @return The {@link ToolCallOutcome} of the execution.
    */
   public ToolCallOutcome execute(ServiceEntry service, String toolName, Map<String, Object> arguments,
                                  Map<String, List<String>> headers) {
      ExpositionEntry exposition = gatewayRegistry.getElectedExpositionByServiceId(service.id());
      if (exposition == null) {
         return new Failure(McpSchema.ErrorCodes.INVALID_PARAMS, "Unknown service: " + service.id(), null);
      }
      return execute(exposition, toolName, arguments, headers);
   }

   /**
    * Verify whether the backend secret of the given configuration requires elicitation.
    * @return an {@link ElicitationRequired}/{@link Failure} outcome if elicitation is needed or
    *         the session is missing, or {@code null} if the call can proceed.
    */
   @Nullable
   private ToolCallOutcome checkBackendSecretElicitation(ServiceEntry service, ConfigurationEntry configuration) {
      SecretEntry secret = configuration.backendSecret();
      if (secret == null || !secret.useElicitation()) {
         return null;
      }

      logger.debugf("Checking elicitation secret value for secret '%s'", secret.name());

      SessionInfo sessionInfo = MethodHandlingContext.getSessionInfo();
      if (sessionInfo != null) {
         // Legacy mode: the secret is bound to the MCP session.
         if (sessionInfo.getSecretValue(secret) != null) {
            return null;
         }
         logger.debugf("Secret value for secret '%s' is missing, initializing session elicitation", secret.name());
         return new ElicitationRequired(List.of(buildElicitation(service, configuration, secret, sessionInfo)));
      }

      // Stateless mode: the secret is bound to the authenticated user identity (iss + sub).
      String userKey = MethodHandlingContext.getUserKey();
      if (userKey == null) {
         logger.warn("Stateless elicitation requires an OAuth-protected exposition (no user identity available)");
         return new Failure(McpSchema.ErrorCodes.INVALID_REQUEST,
               "Elicitation in stateless mode requires an OAuth-protected exposition", null);
      }

      String secretRef = secretRef(service.organizationId(), secret);
      if (userSecretStore.getSecret(userKey, secretRef) != null) {
         return null;
      }

      logger.debugf("Secret value for secret '%s' is missing, initializing user elicitation", secret.name());
      // One opaque resume token for this paused request (mandatory for stateless URL Mode OAuth).
      String requestState = newRequestState();
      return new ElicitationRequired(
            List.of(buildUserElicitation(service, configuration, secret, userKey, requestState)), requestState);
   }

   /** Build a URL elicitation for the given service backend secret and session (legacy mode). */
   private McpSchema.URLElicitation buildElicitation(ServiceEntry service, ConfigurationEntry configuration,
                                                     SecretEntry secret, SessionInfo sessionInfo) {
      String elicitationId = elicitationStore.initializeElicitation(sessionInfo.getId(), service.organizationId(),
            configuration.backendEndpoint(), secret);
      return buildElicitationUrl(elicitationId, secret);
   }

   /** Build a URL elicitation bound to a user identity (stateless mode), carrying the {@code requestState}. */
   private McpSchema.URLElicitation buildUserElicitation(ServiceEntry service, ConfigurationEntry configuration,
                                                         SecretEntry secret, String userKey, String requestState) {
      String elicitationId = elicitationStore.initializeUserElicitation(userKey, service.organizationId(),
            configuration.backendEndpoint(), secret, requestState);
      return buildElicitationUrl(elicitationId, secret);
   }

   /** Build the elicitation URL descriptor shared by both modes. */
   private McpSchema.URLElicitation buildElicitationUrl(String elicitationId, SecretEntry secret) {
      // Adapt elicitation endpoint based on type.
      String elicitationPath = secret.oauth2ClientConfiguration() != null ? "/connect" : "/form";
      String elicitationUrl = WebUtils.getHTTPScheme(fqdns.getFirst()) + fqdns.getFirst() + "/elicitation"
            + elicitationPath + "?elicitationId=" + elicitationId;

      logger.debugf("Elicitation URL is '%s'", elicitationUrl);
      return new McpSchema.URLElicitation(elicitationId, elicitationUrl,
            "Please provide backend secret information by visiting the above URL.");
   }

   /** Build the stable per-user secret reference ({@code organizationId + '/' + secret.name()}). */
   private static String secretRef(String organizationId, SecretEntry secret) {
      return organizationId + '/' + secret.name();
   }

   /** Generate a fresh opaque {@code requestState} resume token for a stateless paused request. */
   private static String newRequestState() {
      return java.util.UUID.randomUUID().toString();
   }

   /**
    * Pre-flight the elicitation requirements of all tools declared before running them. Same-service
    * declared tools are resolved against the <b>current</b> exposition (its own configuration/backend
    * secret), so a script served by a non-elected plan pre-checks the right secret; cross-service tools
    * resolve to the target service's elected exposition.
    * @param currentExposition The exposition the script belongs to.
    * @param declaredTools The tools the script declares it may call.
    * @return {@code null} if the script can run, an {@link ElicitationRequired} aggregating all
    *         unresolved secrets, or a {@link Failure} if a session is required but missing.
    */
   @Nullable
   ToolCallOutcome preflightToolsElicitation(ExpositionEntry currentExposition, List<DeclaredTool> declaredTools) {
      SessionInfo sessionInfo = MethodHandlingContext.getSessionInfo();
      String userKey = sessionInfo == null ? MethodHandlingContext.getUserKey() : null;
      // One opaque resume token shared by all stateless elicitations of this paused request (null in legacy).
      String requestState = sessionInfo == null ? newRequestState() : null;
      List<McpSchema.URLElicitation> elicitations = new ArrayList<>();
      Set<String> seenSecrets = new HashSet<>();

      for (DeclaredTool declaredTool : declaredTools) {
         ExpositionEntry targetExposition = resolveTargetExposition(currentExposition, declaredTool);
         if (targetExposition == null) {
            // Unknown/unauthorized service: it will be rejected at call time, skip here.
            continue;
         }
         ConfigurationEntry configuration = targetExposition.configuration();
         SecretEntry secret = configuration.backendSecret();
         if (secret == null || !secret.useElicitation()) {
            continue;
         }
         ServiceEntry targetService = targetExposition.service();

         if (sessionInfo != null) {
            // Legacy mode: the secret is bound to the MCP session.
            if (sessionInfo.getSecretValue(secret) != null) {
               continue;
            }
            // Deduplicate by target service + secret name to avoid double elicitation.
            if (!seenSecrets.add(targetService.id() + "/" + secret.name())) {
               continue;
            }
            elicitations.add(buildElicitation(targetService, configuration, secret, sessionInfo));
         } else {
            // Stateless mode: the secret is bound to the authenticated user identity (iss + sub).
            if (userKey == null) {
               return new Failure(McpSchema.ErrorCodes.INVALID_REQUEST,
                     "Elicitation in stateless mode requires an OAuth-protected exposition", null);
            }
            String secretRef = secretRef(targetService.organizationId(), secret);
            if (userSecretStore.getSecret(userKey, secretRef) != null) {
               continue;
            }
            // Deduplicate by target service + secret name to avoid double elicitation.
            if (!seenSecrets.add(targetService.id() + "/" + secret.name())) {
               continue;
            }
            elicitations.add(buildUserElicitation(targetService, configuration, secret, userKey, requestState));
         }
      }

      if (elicitations.isEmpty()) {
         return null;
      }
      return new ElicitationRequired(elicitations, requestState);
   }

   /**
    * Resolve the target exposition for a declared tool, restricted to the current organization. Same-service
    * resolves to the current exposition (deterministic); cross-service resolves to the target service's
    * elected exposition (last configuration plan).
    */
   @Nullable
   private ExpositionEntry resolveTargetExposition(ExpositionEntry currentExposition, DeclaredTool declaredTool) {
      if (declaredTool.isSameService()) {
         return currentExposition;
      }
      String[] parts = declaredTool.serviceCoordinate().split(":", 2);
      if (parts.length != 2) {
         return null;
      }
      ServiceEntry targetService = gatewayRegistry.getService(
            currentExposition.service().organizationId(), parts[0], parts[1]);
      if (targetService == null) {
         return null;
      }
      return gatewayRegistry.getElectedExpositionByServiceId(targetService.id());
   }

   /**
    * Build the appropriate {@link McpToolConverter} for the given exposition, wrapping it with the custom
    * tools converter when a CustomTools artifact is attached. Each converter derives its own work cache key
    * from the id of the artifact it parses, so parsed artifacts are shared across expositions referencing the
    * same artifact while staying isolated between tenants (artifact ids are unique TSIDs).
    * @param exposition The exposition to build the converter for.
    * @return The MCP tool converter.
    */
   public McpToolConverter buildMcpToolConverter(ExpositionEntry exposition) {
      ServiceEntry service = exposition.service();

      McpToolConverter converter;
      switch (service.type()) {
         case "GRAPHQL" -> converter = new GraphQLMcpToolConverter(exposition, workCache, mapper, proxyService);
         case "GRPC" -> converter = new GrpcMcpToolConverter(exposition, workCache, mapper, grpcProxyService);
         default -> converter = new OpenAPIMcpToolConverter(exposition, workCache, mapper, proxyService);
      }

      // If we have Custom Tools artifacts attached, wrap converter.
      if (exposition.attachedArtifacts().stream()
            .anyMatch(artifactEntry -> ArtifactEntryType.RESHAPR_CUSTOM_TOOLS.equals(artifactEntry.type()))) {
         converter = new ReshaprCustomToolsMcpToolConverter(exposition, workCache, converter, this, gatewayRegistry);
      }
      return converter;
   }

   /** Build a {@link ToolsOutputFiltersApplier} if a ToolsOutputFilters artifact is attached, else null. */
   @Nullable
   private ToolsOutputFiltersApplier buildToolsOutputFilterApplier(ExpositionEntry exposition) {
      List<ArtifactEntry> attachedArtifacts = exposition.attachedArtifacts();
      if (attachedArtifacts.stream()
            .anyMatch(artifactEntry -> ArtifactEntryType.RESHAPR_TOOLS_OUTPUT_FILTERS.equals(artifactEntry.type()))) {
         return new ToolsOutputFiltersApplier(exposition.service(), attachedArtifacts, workCache);
      }
      return null;
   }

   /**
    * Determine whether an operation is exposed given the configuration include/exclude lists.
    * @param configuration The configuration entry holding include/exclude lists.
    * @param operation The operation to check.
    * @return true if the operation is exposed, false otherwise.
    */
   public static boolean isExposedOperation(ConfigurationEntry configuration, OperationEntry operation) {
      if (!configuration.includedOperations().isEmpty()) {
         return configuration.includedOperations().contains(operation.name());
      }
      if (!configuration.excludedOperations().isEmpty()) {
         return !configuration.excludedOperations().contains(operation.name());
      }
      return true; // No exclusions or inclusions, so all operations are exposed by default.
   }
}

