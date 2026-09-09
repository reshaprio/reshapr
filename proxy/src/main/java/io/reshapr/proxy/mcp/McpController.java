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

import io.reshapr.proxy.audit.AuditEvent;
import io.reshapr.proxy.audit.AuditLogger;
import io.reshapr.proxy.mcp.converters.McpToolConverter;
import io.reshapr.proxy.context.SessionInfo;
import io.reshapr.proxy.mcp.state.SessionStore;
import io.reshapr.proxy.proxy.ProxyService;
import io.reshapr.proxy.context.MethodHandlingInfo;
import io.reshapr.proxy.context.MethodHandlingContext;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.ExpositionEntry;
import io.reshapr.proxy.registry.GatewayRegistry;
import io.reshapr.proxy.registry.ServiceEntry;
import io.reshapr.proxy.security.SecureEndpoint;
import io.reshapr.proxy.security.SecureEndpointFilter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.annotations.AddingSpanAttributes;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

@RunOnVirtualThread
@Path("/mcp")
public class McpController {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   /** Response header advertising the deterministic per-exposition endpoint for a legacy service call. */
   public static final String HEADER_PREFERRED_ENDPOINT = "X-Reshapr-Preferred-Endpoint";

   /**
    * Methods removed by the 2026-07-28 MCP revision. Under a modern (stateless) request these are rejected
    * with HTTP 404 and JSON-RPC {@code -32601} ({@code METHOD_NOT_FOUND}), since the revision dropped them
    * entirely: {@code initialize} / {@code ping} give way to {@code server/discover}, session-bound
    * {@code logging/setLevel} and the {@code resources/subscribe} + {@code resources/unsubscribe} pair are
    * gone with the sessionless model. Methods that survive into 2026 but are simply not implemented keep the
    * ordinary in-band {@code -32601} on HTTP 200.
    */
   private static final Set<String> REMOVED_MODERN_METHODS = Set.of(
         McpSchema.METHOD_INITIALIZE,
         McpSchema.METHOD_PING,
         McpSchema.METHOD_LOGGING_SET_LEVEL,
         McpSchema.METHOD_RESOURCES_SUBSCRIBE,
         McpSchema.METHOD_RESOURCES_UNSUBSCRIBE);

   private final GatewayRegistry gatewayRegistry;
   private final SessionStore sessionStore;
   private final WorkCache workCache;
   private final ProxyService proxyService;
   private final ToolCallExecutor toolCallExecutor;
   private final AuditLogger auditLogger;

   private final ObjectMapper mapper = new ObjectMapper();

   /**
    * Build a McpController with required dependencies.
    * @param gatewayRegistry The registry to access services and configurations.
    * @param sessionStore The store for managing MCP sessions.
    * @param workCache The work cache for temporary data storage.
    * @param proxyService   The proxy service for handling HTTP proxying.
    * @param toolCallExecutor The executor centralizing tool call resolution and invocation.
    * @param auditLogger The audit logger for emitting structured audit events.
    */
   public McpController(GatewayRegistry gatewayRegistry, SessionStore sessionStore,
                        WorkCache workCache, ProxyService proxyService, ToolCallExecutor toolCallExecutor,
                        AuditLogger auditLogger) {
      this.gatewayRegistry = gatewayRegistry;
      this.sessionStore = sessionStore;
      this.workCache = workCache;
      this.proxyService = proxyService;
      this.toolCallExecutor = toolCallExecutor;
      this.auditLogger = auditLogger;
   }

   @POST
   @Path("/{expositionId}")
   @Produces(MediaType.APPLICATION_JSON)
   @SecureEndpoint
   public Response handleHttpStreamable(@PathParam("expositionId") String expositionId,
                                        McpSchema.JSONRPCRequest request, HttpHeaders headers, HttpServerRequest serverRequest,
                                        @Context ContainerRequestContext requestContext) {

      ExpositionEntry exposition = gatewayRegistry.getExpositionById(expositionId);
      if (exposition == null) {
         String errorMsg = String.format("Exposition with id '%s' not found", expositionId);
         logger.warn(errorMsg);
         return Response.status(Response.Status.NOT_FOUND).entity(errorMsg).build();
      }

      return handleMcpRequest(exposition, request, headers, serverRequest, requestContext, null);
   }

   @POST
   @Path("/{organizationId}/{expositionName}")
   @Produces(MediaType.APPLICATION_JSON)
   @SecureEndpoint
   @AddingSpanAttributes
   public Response handleHttpStreamableByName(@SpanAttribute("organizationId") @PathParam("organizationId") String organizationId,
                                              @SpanAttribute("expositionName") @PathParam("expositionName") String expositionName,
                                              McpSchema.JSONRPCRequest request, HttpHeaders headers, HttpServerRequest serverRequest,
                                              @Context ContainerRequestContext requestContext) {

      ExpositionEntry exposition = gatewayRegistry.getExpositionByName(organizationId, expositionName);
      if (exposition == null) {
         String errorMsg = String.format("Exposition '%s' in organization: '%s' not found", expositionName, organizationId);
         logger.warn(errorMsg);
         return Response.status(Response.Status.NOT_FOUND).entity(errorMsg).build();
      }

      return handleMcpRequest(exposition, request, headers, serverRequest, requestContext, null);
   }

   @POST
   @Path("/{organizationId}/{service}/{version}")
   @Produces(MediaType.APPLICATION_JSON)
   @SecureEndpoint
   @AddingSpanAttributes
   public Response handleHttpStreamable(@SpanAttribute("organizationId") @PathParam("organizationId") String organizationId,
                                        @SpanAttribute("service") @PathParam("service") String service,
                                        @SpanAttribute("version") @PathParam("version") String version,
                                        McpSchema.JSONRPCRequest request, HttpHeaders headers, HttpServerRequest serverRequest,
                                        @Context ContainerRequestContext requestContext) {

      // If serviceName was encoded with '+' instead of '%20', remove them.
      if (service.contains("+")) {
         service = service.replace('+', ' ');
      }

      // Legacy endpoint: resolve the elected exposition (last configuration plan) of the service.
      ExpositionEntry exposition = gatewayRegistry.getElectedExpositionByServiceCoordinates(organizationId, service, version);
      if (exposition == null) {
         String errorMsg = String.format("Service '%s', version: '%s' in organization: '%s' not found", service, version, organizationId);
         logger.warn(errorMsg);
         return Response.status(Response.Status.NOT_FOUND).entity(errorMsg).build();
      }

      // Advertise the deterministic per-exposition endpoint that resolves this exact configuration plan.
      return handleMcpRequest(exposition, request, headers, serverRequest, requestContext, buildPreferredEndpoint(exposition));
   }

   /** Build the deterministic endpoint path advertised for a legacy service call (by name when available). */
   private String buildPreferredEndpoint(ExpositionEntry exposition) {
      if (exposition.name() != null && !exposition.name().isBlank()) {
         return "/mcp/" + exposition.service().organizationId() + "/" + exposition.name();
      }
      return "/mcp/" + exposition.id();
   }

   private Response handleMcpRequest(ExpositionEntry exposition, McpSchema.JSONRPCRequest request,
                                     HttpHeaders headers, HttpServerRequest serverRequest,
                                     ContainerRequestContext requestContext, @Nullable String preferredEndpoint) {
      ServiceEntry service = exposition.service();
      if (logger.isDebugEnabled()) {
         logger.debugf("Handling a Mcp Http call on exposition: %s (service %s)", exposition.id(), service.id());
         logger.debugf("Request body: %s", request);
         logger.debugf("Request headers: %s", headers.getRequestHeaders());
      }

      // Enforce the modern (SEP-2243, >= 2026-07-28) pre-dispatch contract before any method resolution.
      // A rejection surfaces the spec error ladder (-32020 -> -32022 -> -32601); legacy calls are untouched.
      Response modernRejection = validateModernRequest(request, headers);
      if (modernRejection != null) {
         return modernRejection;
      }

      // Resolve and validate the protocol mode from headers, except for the handshake/negotiation methods
      // (initialize and server/discover) which happen before any session or version pinning:
      //   - MCP-Session-Id present -> legacy mode (session-based).
      //   - MCP-Session-Id absent  -> stateless mode is only allowed when MCP-Protocol-Version
      //     is exactly the stateless version; otherwise the handshake was skipped/legacy and we reject.
      if (!isHandshakeMethod(request.method()) && !hasSessionHeader(headers)) {
         String protocolVersion = getProtocolVersionHeader(headers);
         if (!McpSchema.PROTOCOL_VERSION_STATELESS.equals(protocolVersion)) {
            logger.warnf("Rejecting MCP call without session id and without stateless protocol version (got '%s')",
                  protocolVersion);
            return Response.ok(buildJSONRPCError(request, McpSchema.ErrorCodes.INVALID_REQUEST,
                  "Missing MCP session: provide a valid '" + McpSchema.HEADER_SESSION_ID
                        + "' header (legacy) or set '" + McpSchema.HEADER_PROTOCOL_VERSION + "' to '"
                        + McpSchema.PROTOCOL_VERSION_STATELESS + "' (stateless).",
                  Map.of("requiredProtocolVersion", McpSchema.PROTOCOL_VERSION_STATELESS,
                        "receivedProtocolVersion", protocolVersion == null ? "" : protocolVersion))).build();
         }
      }

      // Extract userId and issuer from request context (set by SecureEndpointFilter after OAuth2 validation).
      String userId = (String) requestContext.getProperty(SecureEndpointFilter.USER_ID_PROPERTY);
      String issuer = (String) requestContext.getProperty(SecureEndpointFilter.ISSUER_PROPERTY);

      AtomicReference<McpHandlerResult> resultRef = new AtomicReference<>();
      long startNanos = System.nanoTime();
      try {
         // Scope the call with call + session info for those who need it.
         MethodHandlingInfo handlingInfo = new MethodHandlingInfo(
               serverRequest.remoteAddress().host(), getSessionInfo(headers),
               userId, issuer, service.organizationId());
         ScopedValue.where(MethodHandlingContext.METHOD_HANDLING_INFO, handlingInfo).run(() -> {
            resultRef.set(handleMcpRequest(exposition, request, headers));
         });

         // Compose a Response based on result. The HTTP status is derived from the JSON-RPC error code (if
         // any) via the modern transport mapping, so protocol-level errors surface with 4xx instead of a
         // blanket 200; handler-produced errors (e.g. -32602) stay in-band on 200.
         McpHandlerResult result = resultRef.get();

         // Emit audit log if enabled for this configuration.
         emitAuditEvent(exposition, request, result, startNanos, serverRequest, userId);

         Response.ResponseBuilder responseBuilder = Response.status(httpStatusForMessage(result.message()))
               .entity(result.message());
         if (result.headers() != null) {
            result.headers().forEach((key, value) -> value.forEach(
                  headerValue -> responseBuilder.header(key, headerValue)
            ));
         }

         // Advertise the deterministic per-exposition endpoint when serving a legacy service call.
         if (preferredEndpoint != null) {
            responseBuilder.header(HEADER_PREFERRED_ENDPOINT, preferredEndpoint);
         }

         // Now add the mandatory MCP headers bound to session.
         if (getSessionInfo(headers) != null) {
            SessionInfo sessionInfo = getSessionInfo(headers);
            if (sessionInfo != null) {
               logger.debugf("Adding MCP session headers for session id: %s", sessionInfo.getId());
               responseBuilder
                     .header(McpSchema.HEADER_SESSION_ID, sessionInfo.getId())
                     .header(McpSchema.HEADER_PROTOCOL_VERSION, sessionInfo.getProtocolVersion());
            }
         }

         return responseBuilder.build();
      } catch (McpError e) {
         return Response.status(Response.Status.BAD_REQUEST).entity(e).build();
      }
   }

   @Nullable
   private SessionInfo getSessionInfo(HttpHeaders headers) {
      if (headers.getRequestHeader(McpSchema.HEADER_SESSION_ID) != null &&
            !headers.getRequestHeader(McpSchema.HEADER_SESSION_ID).isEmpty()) {
         String sessionId = headers.getRequestHeader(McpSchema.HEADER_SESSION_ID).getFirst();
         return sessionStore.getSessionInfo(sessionId);
      }
      return null;
   }

   /** Whether the request carries a non-blank MCP session id header (i.e. legacy session mode). */
   private boolean hasSessionHeader(HttpHeaders headers) {
      List<String> values = headers.getRequestHeader(McpSchema.HEADER_SESSION_ID);
      return values != null && !values.isEmpty()
            && values.getFirst() != null && !values.getFirst().isBlank();
    }

    /**
     * Whether the given method is a handshake/negotiation method that runs before any session exists or any
     * protocol version has been pinned ({@code server/discover} and {@code initialize}). These must bypass the
     * session/protocol-version dispatch guard, otherwise version negotiation can never complete.
     */
    private static boolean isHandshakeMethod(String method) {
       return McpSchema.METHOD_SERVER_DISCOVER.equals(method) || McpSchema.METHOD_INITIALIZE.equals(method);
    }

   /** Return the MCP-Protocol-Version header value if present, or {@code null}. */
   @Nullable
   private String getProtocolVersionHeader(HttpHeaders headers) {
      return getHeader(headers, McpSchema.HEADER_PROTOCOL_VERSION);
   }

   /** Return the first value of the given request header if present and non-empty, or {@code null}. */
   @Nullable
   private String getHeader(HttpHeaders headers, String name) {
      List<String> values = headers.getRequestHeader(name);
      return (values != null && !values.isEmpty()) ? values.getFirst() : null;
   }

   /**
    * Enforce the modern (SEP-2243, {@code >= 2026-07-28}) pre-dispatch contract for a stateless call, in the
    * spec error-ladder order: {@code -32020} (mirror-header mismatch) &rarr; {@code -32022} (unsupported
    * protocol version) &rarr; {@code -32601} (method removed by the revision). Returns the first rejection
    * produced by that ladder, or {@code null} when the request may proceed to dispatch (including every
    * legacy call, which matches none of the modern gates).
    *
    * Each rung keeps its own modern-mode detection because the gates legitimately differ (see the
    * individual methods): the header contract requires a valid modern envelope, version negotiation keys off
    * the envelope's mere presence so an <em>unsupported</em> version is still named, and removed-method
    * rejection additionally accepts the {@code MCP-Protocol-Version} header as a fallback for envelope-less
    * modern calls.
    *
    * @param request The JSON-RPC request to validate.
    * @param headers The HTTP headers carrying the modern mirror/protocol headers.
    * @return the first modern-contract rejection response, or {@code null} when the call may proceed.
    */
   @Nullable
   private Response validateModernRequest(McpSchema.JSONRPCRequest request, HttpHeaders headers) {
      Response headerMismatch = validateModernHeaders(request, headers);
      if (headerMismatch != null) {
         return headerMismatch;
      }
      Response unsupportedVersion = validateModernProtocolVersion(request);
      if (unsupportedVersion != null) {
         return unsupportedVersion;
      }
      return rejectRemovedModernMethod(request, headers);
   }

   /**
    * Enforce the modern (SEP-2243, {@code >= 2026-07-28}) request mirror-header contract for a stateless
    * call: the {@code Mcp-Method}, {@code Mcp-Name} and {@code MCP-Protocol-Version} headers MUST mirror
    * the request body. A disagreement is rejected with HTTP 400 and JSON-RPC {@code -32020} before the
    * request is dispatched.
    *
    * The contract applies only to modern (stateless) requests, detected by the negotiated protocol
    * version carried in {@code params._meta}. A legacy call carries no modern envelope and none of these
    * mirror headers, so it is left untouched (returns {@code null}).
    *
    * @param request The JSON-RPC request whose body the headers must mirror.
    * @param headers The HTTP headers carrying the modern mirror headers.
    * @return a 400 response describing the mismatch, or {@code null} when the headers agree (or the call is legacy).
    */
   @Nullable
   private Response validateModernHeaders(McpSchema.JSONRPCRequest request, HttpHeaders headers) {
      String envelopeVersion = getEnvelopeProtocolVersion(request);
      if (envelopeVersion == null || !McpSchema.isAtLeast(envelopeVersion, McpSchema.PROTOCOL_VERSION_STATELESS)) {
         // Not a modern/stateless call — the mirror-header contract does not apply.
         return null;
      }

      // Mcp-Method MUST equal the JSON-RPC body method.
      String methodHeader = getHeader(headers, McpSchema.HEADER_METHOD);
      if (methodHeader != null && !methodHeader.equals(request.method())) {
         return buildHeaderMismatchResponse(request, McpSchema.HEADER_METHOD, request.method(), methodHeader);
      }

      // Mcp-Name, when present, MUST equal the body target (params.name / params.uri).
      String nameHeader = getHeader(headers, McpSchema.HEADER_NAME);
      if (nameHeader != null) {
         String target = getRequestTargetName(request);
         if (!nameHeader.equals(target)) {
            return buildHeaderMismatchResponse(request, McpSchema.HEADER_NAME, target, nameHeader);
         }
      }

      // MCP-Protocol-Version, when present, MUST equal the envelope protocol version.
      String versionHeader = getProtocolVersionHeader(headers);
      if (versionHeader != null && !versionHeader.equals(envelopeVersion)) {
         return buildHeaderMismatchResponse(request, McpSchema.HEADER_PROTOCOL_VERSION, envelopeVersion, versionHeader);
      }

      return null;
   }

   /** Build the HTTP 400 + JSON-RPC {@code -32020} response for a modern mirror-header mismatch. */
   private Response buildHeaderMismatchResponse(McpSchema.JSONRPCRequest request, String header,
         @Nullable String expected, String received) {
      logger.warnf("Rejecting modern MCP call: header '%s'='%s' disagrees with request body value '%s'",
            header, received, expected);
      return buildErrorResponse(request, McpSchema.ErrorCodes.HEADER_MISMATCH,
            "Header '" + header + "' does not match the request body",
            Map.of("header", header, "expected", expected == null ? "" : expected, "received", received));
   }

   /**
    * Reject a method removed by the 2026-07-28 revision when the request is modern (stateless): the removed
    * methods (see {@link #REMOVED_MODERN_METHODS}) answer HTTP 404 with JSON-RPC {@code -32601}. A legacy
    * call — where these methods are still valid — is left untouched (returns {@code null}).
    *
    * @param request The JSON-RPC request whose method may have been removed.
    * @param headers The HTTP headers used, together with the envelope, to detect the modern mode.
    * @return a 404 response for a removed method under a modern call, or {@code null} otherwise.
    */
   @Nullable
   private Response rejectRemovedModernMethod(McpSchema.JSONRPCRequest request, HttpHeaders headers) {
      if (!isModernRequest(request, headers) || !REMOVED_MODERN_METHODS.contains(request.method())) {
         return null;
      }
      logger.warnf("Rejecting method '%s' removed by the 2026-07-28 MCP revision (modern/stateless mode)",
            request.method());
      return Response.status(Response.Status.NOT_FOUND)
            .entity(buildJSONRPCError(request, McpSchema.ErrorCodes.METHOD_NOT_FOUND,
                  "Method '" + request.method() + "' was removed in protocol " + McpSchema.PROTOCOL_VERSION_STATELESS,
                  null))
            .build();
   }

   /**
    * Whether the request is a modern (stateless, {@code >= 2026-07-28}) call. Detected first from the
    * negotiated protocol version carried in the modern envelope ({@code params._meta}), falling back to the
    * {@code MCP-Protocol-Version} header for a modern request that carries no envelope (e.g. a bare
    * {@code tools/list}). A legacy call carries neither and is reported as non-modern.
    */
   private boolean isModernRequest(McpSchema.JSONRPCRequest request, HttpHeaders headers) {
      String envelopeVersion = getEnvelopeProtocolVersion(request);
      if (envelopeVersion != null) {
         return McpSchema.isAtLeast(envelopeVersion, McpSchema.PROTOCOL_VERSION_STATELESS);
      }
      String headerVersion = getProtocolVersionHeader(headers);
      return headerVersion != null && McpSchema.isAtLeast(headerVersion, McpSchema.PROTOCOL_VERSION_STATELESS);
   }

   /**
    * Reject a modern request whose envelope declares a protocol version the server does not support: HTTP
    * 400 with JSON-RPC {@code -32022}, the error {@code data} naming the supported versions. A version an
    * unknown revision might introduce is caught here even for the handshake methods ({@code server/discover},
    * {@code initialize}), since the envelope carries the negotiated version on every modern call.
    *
    * <p>Detection keys off the presence of the modern envelope ({@code params._meta} protocol version) rather
    * than {@link #isModernRequest}: an <em>unsupported</em> version is by definition not
    * {@code >= 2026-07-28}, yet a modern client that framed the call with the envelope MUST still be told the
    * version is unsupported. A legacy call carries no envelope and negotiates through {@code initialize}
    * instead, so it is left untouched (returns {@code null}).</p>
    *
    * @param request The JSON-RPC request whose modern envelope may declare an unsupported version.
    * @return a 400 response naming the supported versions, or {@code null} when the version is supported or the call is legacy.
    */
   @Nullable
   private Response validateModernProtocolVersion(McpSchema.JSONRPCRequest request) {
      String declaredVersion = getEnvelopeProtocolVersion(request);
      if (declaredVersion == null || McpSchema.SUPPORTED_PROTOCOL_VERSIONS.contains(declaredVersion)) {
         return null;
      }
      logger.warnf("Rejecting modern MCP call declaring unsupported protocol version '%s'", declaredVersion);
      return buildErrorResponse(request, McpSchema.ErrorCodes.UNSUPPORTED_PROTOCOL_VERSION,
            "Unsupported protocol version: " + declaredVersion,
            Map.of("supported", McpSchema.SUPPORTED_PROTOCOL_VERSIONS, "requested", declaredVersion));
   }

   /**
    * Read the modern envelope protocol version carried in {@code params._meta}
    * (key {@link McpSchema#META_KEY_PROTOCOL_VERSION}), or {@code null} when the request carries no
    * modern envelope (i.e. a legacy call).
    */
   @Nullable
   private String getEnvelopeProtocolVersion(McpSchema.JSONRPCRequest request) {
      if (!(request.params() instanceof Map<?, ?> paramsMap)) {
         return null;
      }
      if (!(paramsMap.get("_meta") instanceof Map<?, ?> metaMap)) {
         return null;
      }
      return metaMap.get(McpSchema.META_KEY_PROTOCOL_VERSION) instanceof String version ? version : null;
   }

   /**
    * Read the body target the {@code Mcp-Name} header mirrors: {@code params.name} (tools/call,
    * prompts/get) or, failing that, {@code params.uri} (resources/read). {@code null} when the request
    * body names no target.
    */
   @Nullable
   private String getRequestTargetName(McpSchema.JSONRPCRequest request) {
      if (!(request.params() instanceof Map<?, ?> paramsMap)) {
         return null;
      }
      if (paramsMap.get("name") instanceof String name) {
         return name;
      }
      return paramsMap.get("uri") instanceof String uri ? uri : null;
   }

   /**
    * Handle the MCP request and return a JSONRPCResponse.
    * @param exposition The exposition for which the request is made.
    * @param request The JSONRPCRequest to handle.
    * @param headers The HTTP headers associated with the request.
    * @return A JSONRPCMessage representing the result of the request handling.
    */
   private McpHandlerResult handleMcpRequest(ExpositionEntry exposition, McpSchema.JSONRPCRequest request, HttpHeaders headers) {
      McpHandlerResult result = null;
      switch (request.method()) {
         case McpSchema.METHOD_SERVER_DISCOVER ->
            result = handleServerDiscoverRequest(request, exposition);

         case McpSchema.METHOD_INITIALIZE ->
            result = handleInitializeRequest(request, exposition);

         case McpSchema.METHOD_PROMPTS_LIST ->
            result = handlePromptListRequest(request, exposition,
                  McpProtocolDialect.forVersion(resolveProtocolVersion(headers)));

         case McpSchema.METHOD_PROMPTS_GET ->
            result = handlePromptGetRequest(request, exposition);

         case McpSchema.METHOD_RESOURCES_LIST ->
            result = handleResourceListRequest(request, exposition,
                  McpProtocolDialect.forVersion(resolveProtocolVersion(headers)));

         case McpSchema.METHOD_RESOURCES_TEMPLATES_LIST ->
            result = handleResourceTemplateListRequest(request, exposition,
                  McpProtocolDialect.forVersion(resolveProtocolVersion(headers)));

         case McpSchema.METHOD_RESOURCES_READ ->
            result = handleResourceReadRequest(request, exposition,
                  McpProtocolDialect.forVersion(resolveProtocolVersion(headers)));

         case McpSchema.METHOD_TOOLS_LIST ->
            result = handleToolsListRequest(request, exposition,
                  McpProtocolDialect.forVersion(resolveProtocolVersion(headers)));

         case McpSchema.METHOD_TOOLS_CALL ->
            result = handleToolsCallRequest(request, headers.getRequestHeaders(), exposition,
                  McpProtocolDialect.forVersion(resolveProtocolVersion(headers)));
      }

      if (result == null) {
         // No result means method not found JSONRPCError.
         logger.warnf("Unsupported MCP method: %s", request.method());
         return new McpHandlerResult(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), null,
               new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.METHOD_NOT_FOUND,
                     "Unsupported method: " + request.method(), null)), null);
      }
      return result;
   }

   private record McpHandlerResult(
         McpSchema.JSONRPCMessage message,
         @Nullable Map<String, List<String>> headers
   ) {
      public boolean isJSONRPCRequest() {
         return message instanceof McpSchema.JSONRPCRequest;
      }
      public boolean isJSONRPCResponse() {
         return message instanceof McpSchema.JSONRPCResponse;
      }
      /**
       * Return a copy of this result carrying the given extra headers (merged over the existing ones,
       * incoming values winning). An empty/null map returns {@code this} unchanged; when this result
       * has no headers of its own, the incoming map is adopted as-is to avoid a needless copy.
       */
      public McpHandlerResult withHeaders(@Nullable Map<String, List<String>> extra) {
         if (extra == null || extra.isEmpty()) {
            return this;
         }
         if (headers == null || headers.isEmpty()) {
            return new McpHandlerResult(message, extra);
         }
         // Both sides non-empty: merge with a pre-sized HashMap (order is semantically irrelevant for HTTP).
         Map<String, List<String>> merged = new HashMap<>(headers.size() + extra.size(), 1.0f);
         merged.putAll(headers);
         merged.putAll(extra);
         return new McpHandlerResult(message, merged);
      }
   }

   /** Handle the MCP server/discover request. */
   private McpHandlerResult handleServerDiscoverRequest(McpSchema.JSONRPCRequest request, ExpositionEntry exposition) {
      ServiceEntry service = exposition.service();
      McpSchema.ServerCapabilities serverCapabilities = new McpSchema.ServerCapabilities(null, null,
            new McpSchema.ServerCapabilities.PromptCapabilities(false),
            new McpSchema.ServerCapabilities.ResourceCapabilities(false, false),
            new McpSchema.ServerCapabilities.ToolCapabilities(false));

      McpSchema.Implementation serverInfo =
            new McpSchema.Implementation(service.name() + " MCP server", service.version());

      Map<String, Object> meta = Map.of(
            "io.modelcontextprotocol/serverInfo", serverInfo
      );

      ConfigurationEntry configuration = exposition.configuration();

      McpSchema.DiscoverResult discoverResult = new McpSchema.DiscoverResult(
            McpSchema.SUPPORTED_PROTOCOL_VERSIONS,
            serverCapabilities,
            serverInfo,
            meta,
            getCacheTtlMs(configuration),
            getCacheScope(configuration));

      return toMcpHandlerResult(request, discoverResult);
   }

   /** Handle the MCP initialize request. */
   private McpHandlerResult handleInitializeRequest(McpSchema.JSONRPCRequest request, ExpositionEntry exposition) {
      ServiceEntry service = exposition.service();
      McpSchema.InitializeRequest initializeRequest = mapper.convertValue(request.params(),
            new TypeReference<McpSchema.InitializeRequest>() {
            });

      if (McpSchema.SUPPORTED_PROTOCOL_VERSIONS.contains(initializeRequest.protocolVersion())) {
         McpSchema.ClientCapabilities clientCapabilities = initializeRequest.capabilities();
         McpSchema.Implementation clientInfo = initializeRequest.clientInfo();

         McpSchema.ServerCapabilities serverCapabilities = new McpSchema.ServerCapabilities(null, null,
               new McpSchema.ServerCapabilities.PromptCapabilities(false),
               new McpSchema.ServerCapabilities.ResourceCapabilities(false, false),
               new McpSchema.ServerCapabilities.ToolCapabilities(false));

         McpSchema.JSONRPCResponse response = buildJSONRPCResponse(request,
               new McpSchema.InitializeResult(initializeRequest.protocolVersion(), serverCapabilities,
                     new McpSchema.Implementation(service.name() + " MCP server", service.version()), null));

         // Stateless mode (>= 2026-07-28): no server-side session is created. The client must send
         // MCP-Protocol-Version on every subsequent request; elicited secrets are bound to the user.
         if (McpSchema.PROTOCOL_VERSION_STATELESS.equals(initializeRequest.protocolVersion())) {
            logger.debugf("Initializing stateless MCP session (protocol %s) for service '%s'",
                  initializeRequest.protocolVersion(), service.id());
            return new McpHandlerResult(response, null);
         }

         // Legacy mode (< 2026-07-28): create a server-side session and advertise its id.
         String sessionId = sessionStore.initializeSession(service.id(), initializeRequest.protocolVersion());
         Map<String, List<String>> responseHeaders = Map.of(McpSchema.HEADER_SESSION_ID,
               List.of(sessionId));

         return new McpHandlerResult(response, responseHeaders);
      }
      // Return error for unsupported protocol version.
      return new McpHandlerResult(buildJSONRPCError(request, McpSchema.ErrorCodes.INVALID_PARAMS,
            "Unsupported protocol version: " + initializeRequest.protocolVersion(),
            Map.of("supported", McpSchema.SUPPORTED_PROTOCOL_VERSIONS, "requested", initializeRequest.protocolVersion())), null);
   }

   /** Handle the MCP prompt/list request. */
   private McpHandlerResult handlePromptListRequest(McpSchema.JSONRPCRequest request, ExpositionEntry exposition,
         McpProtocolDialect dialect) {
      // Build a MCP Prompt Builder based on available elements in registry.
      McpPromptBuilder builder = buildMcpPromptBuilder(exposition);

      // Delegate the version-specific result shaping to the negotiated protocol dialect. The modern
      // client-cache hints are always provided here; they are honored only under a modern dialect and
      // silently dropped in legacy mode.
      ConfigurationEntry configuration = exposition.configuration();
      McpSchema.ListPromptsResult result = dialect.newListPromptsResult(builder.listPrompts())
            .ttlMs(getCacheTtlMs(configuration))
            .cacheScope(getCacheScope(configuration))
            .build();

      return toMcpHandlerResult(request, result);
   }

   /** Handle the MCP prompt/get request. */
   private McpHandlerResult handlePromptGetRequest(McpSchema.JSONRPCRequest request, ExpositionEntry exposition) {
      McpSchema.SimpleRequest promptGetRequest = mapper.convertValue(request.params(),
            new TypeReference<McpSchema.SimpleRequest>() {
            });

      // Build a MCP Prompt Builder based on available elements in registry.
      McpPromptBuilder builder = buildMcpPromptBuilder(exposition);

      McpSchema.PromptMessage prompt = builder.getPrompt(promptGetRequest);

      return toMcpHandlerResult(request, new McpSchema.GetPromptResult(null, List.of(prompt)));
   }

   /** Handle the MCP resource/list request. */
   private McpHandlerResult handleResourceListRequest(McpSchema.JSONRPCRequest request, ExpositionEntry exposition,
         McpProtocolDialect dialect) {
      // Build a MCP Resource Builder based on available elements in registry.
      McpResourceBuilder builder = buildMcpResourceBuilder(exposition);

      // Delegate the version-specific result shaping to the negotiated protocol dialect. The modern
      // client-cache hints are always provided here; they are honored only under a modern dialect and
      // silently dropped in legacy mode.
      ConfigurationEntry configuration = exposition.configuration();
      McpSchema.ListResourcesResult result = dialect.newListResourcesResult(builder.listResources())
            .ttlMs(getCacheTtlMs(configuration))
            .cacheScope(getCacheScope(configuration))
            .build();

      return toMcpHandlerResult(request, result);
   }

   /** Handle the MCP resource/templates/list request. */
   private McpHandlerResult handleResourceTemplateListRequest(McpSchema.JSONRPCRequest request, ExpositionEntry exposition,
         McpProtocolDialect dialect) {
      // Build a MCP Resource Builder based on available elements in registry.
      McpResourceBuilder builder = buildMcpResourceBuilder(exposition);

      // Delegate the version-specific result shaping to the negotiated protocol dialect. The modern
      // client-cache hints are always provided here; they are honored only under a modern dialect and
      // silently dropped in legacy mode.
      ConfigurationEntry configEntry = exposition.configuration();
      McpSchema.ListResourceTemplatesResult result = dialect.newListResourceTemplatesResult(builder.listResourceTemplates())
            .ttlMs(getCacheTtlMs(configEntry))
            .cacheScope(getCacheScope(configEntry))
            .build();

      return toMcpHandlerResult(request, result);
   }

   /** Handle the MCP resource/read request. */
   private McpHandlerResult handleResourceReadRequest(McpSchema.JSONRPCRequest request, ExpositionEntry exposition,
         McpProtocolDialect dialect) {
      McpSchema.ReadResourceRequest resourceReadRequest = mapper.convertValue(request.params(),
            new TypeReference<McpSchema.ReadResourceRequest>() {
            });

      // Get configuration plan from exposition.
      ConfigurationEntry configuration = exposition.configuration();

      // Build a MCP Resource Builder based on available elements in registry.
      McpResourceBuilder builder = buildMcpResourceBuilder(exposition);

      List<McpSchema.ResourceContents> contents = builder.readResource(resourceReadRequest, configuration);
      if (contents == null) {
         // No resource matches the requested URI: return an in-band JSON-RPC error (HTTP 200) as
         // mandated by the MCP spec conformance checks (invalid params).
         return toMcpHandlerResult(request, McpSchema.ErrorCodes.INVALID_PARAMS,
               "Resource not found: " + resourceReadRequest.uri(),
               Map.of("uri", resourceReadRequest.uri()));
      }

      // Delegate the version-specific result shaping to the negotiated protocol dialect. The modern
      // client-cache hints are always provided here; they are honored only under a modern dialect and
      // silently dropped in legacy mode.
      McpSchema.ReadResourceResult result = dialect.newReadResourceResult(contents)
            .ttlMs(getCacheTtlMs(configuration))
            .cacheScope(getCacheScope(configuration))
            .build();

      return toMcpHandlerResult(request, result);
   }

   /**
    * Resolve the protocol version negotiated for this call: the pinned version carried by the legacy
    * session when present, otherwise the {@code MCP-Protocol-Version} header sent in stateless mode.
    * Returns {@code null} when neither is available (dialect resolution then falls back to legacy).
    */
   @Nullable
   private String resolveProtocolVersion(HttpHeaders headers) {
      SessionInfo sessionInfo = getSessionInfo(headers);
      if (sessionInfo != null && sessionInfo.getProtocolVersion() != null) {
         return sessionInfo.getProtocolVersion();
      }
      return getProtocolVersionHeader(headers);
   }

   /** Handle the MCP tools/list request. */
   private McpHandlerResult handleToolsListRequest(McpSchema.JSONRPCRequest request, ExpositionEntry exposition,
         McpProtocolDialect dialect) {
      ServiceEntry service = exposition.service();
      // Get configuration plan from exposition.
      ConfigurationEntry configuration = exposition.configuration();

      // Build converter based on service type.
      McpToolConverter converter = toolCallExecutor.buildMcpToolConverter(exposition);

      List<McpSchema.Tool> tools = converter.getExposedOperations(service, configuration).stream()
            .map(operation -> new McpSchema.Tool(converter.getToolName(operation),
                  converter.getToolDescription(operation), converter.getInputSchema(operation),
                  converter.getToolMetadata(gatewayRegistry, service, operation)))
            .toList();

      // Delegate the version-specific result shaping to the negotiated protocol dialect. The modern
      // client-cache hints are always provided here; they are honored only under a modern dialect and
      // silently dropped in legacy mode.
      McpSchema.ListToolsResult result = dialect.newListToolsResult(tools)
            .ttlMs(getCacheTtlMs(configuration))
            .cacheScope(getCacheScope(configuration))
            .build();

      return toMcpHandlerResult(request, result);
   }

   /** Handle the MCP tools/call request. */
   private McpHandlerResult handleToolsCallRequest(McpSchema.JSONRPCRequest request, Map<String, List<String>> headers,
         ExpositionEntry exposition, McpProtocolDialect dialect) {
      String toolName = null;
      Map<String, Object> arguments = null;
      if (request.params() instanceof Map<?, ?> paramsMap) {
         toolName = paramsMap.get("name").toString();
         if (paramsMap.get("arguments") instanceof Map<?, ?> args) {
            // Shallow defensive copy: converters may add/remove top-level entries.
            arguments = new HashMap<>((Map<String, Object>) args);
         }
      }

      // Delegate the whole tool call resolution and invocation to the executor.
      ToolCallExecutor.ToolCallOutcome outcome = toolCallExecutor.execute(exposition, toolName, arguments, headers);

      return switch (outcome) {
         case ToolCallExecutor.Success success ->
               // Delegate the version-specific result shaping to the negotiated protocol dialect,
               // then surface the aggregated response attributes (e.g. X-Reshapr-Upstream-Service-Time)
               // as HTTP headers on the outgoing MCP response.
               toMcpHandlerResult(request, dialect.newCallToolResult(
                     List.<McpSchema.Content>of(new McpSchema.TextContent(success.content())))
                           .isError(success.isFault())
                           .build())
                     .withHeaders(success.attrs().toHttpHeaders());
         case ToolCallExecutor.ElicitationRequired elicitationRequired ->
               buildElicitationResult(request, elicitationRequired);
         case ToolCallExecutor.Failure failure ->
               toMcpHandlerResult(request, failure.code(), failure.message(), failure.data());
      };
   }

   /**
    * Render an {@link ToolCallExecutor.ElicitationRequired} outcome according to the current mode:
    * <ul>
    *   <li><b>legacy</b> (session bound) ⇒ a {@code URL_ELICITATION_REQUIRED} JSON-RPC error carrying the
    *       elicitations (unchanged pre-{@code 2026-07-28} behavior);</li>
    *   <li><b>stateless</b> ({@code >= 2026-07-28}) ⇒ an {@code InputRequiredResult} wrapping one
    *       {@code elicitation/create} ("URL Mode") request per unresolved secret.</li>
    * </ul>
    */
   private McpHandlerResult buildElicitationResult(McpSchema.JSONRPCRequest request,
         ToolCallExecutor.ElicitationRequired elicitationRequired) {
      if (MethodHandlingContext.isStateless()) {
         return toMcpHandlerResult(request, McpSchema.buildInputRequiredResult(
               elicitationRequired.elicitations(), elicitationRequired.requestState()));
      }
      return toMcpHandlerResult(request,
            McpSchema.buildURLElicitationRequiredError(elicitationRequired.elicitations()));
   }

   /** Source ttlMs from the configuration's cachePolicy, falling back to defaults. */
   private static Long getCacheTtlMs(ConfigurationEntry configurationEntry) {
      ConfigurationEntry.CachePolicyEntry cachingEntry = configurationEntry.cachePolicy();
      return cachingEntry != null ? cachingEntry.effectiveTtlMs() : ConfigurationEntry.CachePolicyEntry.DEFAULT_TTL_MS;
   }

   /** Source cacheScope from the configuration's cachePolicy, falling back to defaults. */
   private static String getCacheScope(ConfigurationEntry configurationEntry) {
      ConfigurationEntry.CachePolicyEntry cachingEntry = configurationEntry.cachePolicy();
      return cachingEntry != null ? cachingEntry.effectiveCacheScope() : ConfigurationEntry.CachePolicyEntry.DEFAULT_CACHE_SCOPE;
   }

   private static McpHandlerResult toMcpHandlerResult(McpSchema.JSONRPCRequest request, Object result) {
      return new McpHandlerResult(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), result, null),
            null);
   }

   private static McpHandlerResult toMcpHandlerResult(McpSchema.JSONRPCRequest request, int code, String message, Object data) {
      return new McpHandlerResult(
            buildJSONRPCError(request, code, message, data),
            null);
   }

   private static McpHandlerResult toMcpHandlerResult(McpSchema.JSONRPCRequest request, McpSchema.JSONRPCResponse.JSONRPCError error) {
      return new McpHandlerResult(
            new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), null, error),
            null);
   }

   private static McpSchema.JSONRPCResponse buildJSONRPCResponse(McpSchema.JSONRPCRequest request, Object result) {
      return new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), result, null);
   }

   private static McpSchema.JSONRPCResponse buildJSONRPCError(McpSchema.JSONRPCRequest request, int code, String message, Object data) {
      return new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), null,
         new McpSchema.JSONRPCResponse.JSONRPCError(code, message, data));
   }

   /**
    * The HTTP status the modern transport surfaces a JSON-RPC error code with. Protocol-level ladder errors
    * ({@code -32020} header mismatch, {@code -32021} missing client capability, {@code -32022} unsupported
    * version) map to {@code 400}; every other code — including handler-produced ones such as {@code -32602}
    * (invalid params) — stays in-band on {@code 200}. Methods removed by the 2026 revision are a separate
    * {@code 404} path (see {@link #rejectRemovedModernMethod}) because a generic {@code -32601} for a
    * merely-unimplemented method must stay {@code 200}.
    */
   private static Response.Status httpStatusForErrorCode(int code) {
      return switch (code) {
         case McpSchema.ErrorCodes.HEADER_MISMATCH,
              McpSchema.ErrorCodes.MISSING_CLIENT_CAPABILITY,
              McpSchema.ErrorCodes.UNSUPPORTED_PROTOCOL_VERSION -> Response.Status.BAD_REQUEST;
         default -> Response.Status.OK;
      };
   }

   /** The HTTP status for a composed handler message: derived from its JSON-RPC error code, else {@code 200}. */
   private static Response.Status httpStatusForMessage(McpSchema.JSONRPCMessage message) {
      if (message instanceof McpSchema.JSONRPCResponse response && response.error() != null) {
         return httpStatusForErrorCode(response.error().code());
      }
      return Response.Status.OK;
   }

   /** Build an HTTP response for a JSON-RPC error, deriving the status from the error code (modern mapping). */
   private Response buildErrorResponse(McpSchema.JSONRPCRequest request, int code, String message, Object data) {
      return Response.status(httpStatusForErrorCode(code))
            .entity(buildJSONRPCError(request, code, message, data))
            .build();
   }

   private McpPromptBuilder buildMcpPromptBuilder(ExpositionEntry exposition) {
      return new ReshaprPromptsMcpPromptBuilder(exposition.service(),
            exposition.attachedArtifacts(), workCache, mapper);
   }

   private McpResourceBuilder buildMcpResourceBuilder(ExpositionEntry exposition) {
      return new ReshaprResourcesMcpResourceBuilder(exposition.service(),
            exposition.attachedArtifacts(), workCache, mapper, proxyService);
   }


   /**
    * Emit an audit event asynchronously if audit logging is enabled for this exposition's configuration.
    * Runs on a virtual thread to avoid impacting the request response time.
    */
   private void emitAuditEvent(ExpositionEntry exposition, McpSchema.JSONRPCRequest request,
                               McpHandlerResult result, long startNanos,
                               HttpServerRequest serverRequest, @Nullable String userId) {
      ServiceEntry service = exposition.service();
      ConfigurationEntry configuration = exposition.configuration();
      if (configuration == null || !configuration.audit()) {
         logger.debugf("Audit logging is not enabled for config on service '%s'", service.id());
         return;
      }

      logger.debugf("Audit logging is enabled for config on service '%s', emitting audit event", service.id());
      // Capture duration now (before async handoff) so it reflects actual processing time.
      long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

      // Capture trace context now — the span is bound to the current thread and won't be
      // available on the virtual thread used for async emission.
      Span currentSpan = Span.current();
      String traceId = currentSpan.getSpanContext().isValid() ? currentSpan.getSpanContext().getTraceId() : null;

      // Capture all request-scoped values synchronously before async handoff.
      // serverRequest is backed by a Vert.x connection context that is recycled after the HTTP
      // response completes — accessing remoteAddress() or getHeader() on a virtual thread
      // scheduled after that point causes intermittent IllegalStateException and silent audit loss.
      String method = request.method();
      Object requestId = request.id();
      String serviceName = service.name();
      String serviceVersion = service.version();
      String organizationId = service.organizationId();
      String sourceIp = serverRequest.remoteAddress() != null ? serverRequest.remoteAddress().host() : null;
      SessionInfo sessionInfo = getSessionInfo(serverRequest);
      String sessionId = sessionInfo != null ? sessionInfo.getId() : null;

      final McpSchema.JSONRPCRequest finalRequest = request;

      // Execute audit event sending asynchronously.
      Thread.startVirtualThread(() -> {
         // Extract the target name with a direct cast on the already-deserialized params map
         // — no deep Jackson re-conversion on the virtual thread.
         String targetName = getRequestTargetName(finalRequest);

         // Determine outcome and error code from the result.
         String outcome = AuditEvent.OUTCOME_SUCCESS;
         Integer errorCode = null;
         if (result.isJSONRPCResponse()
               && result.message() instanceof McpSchema.JSONRPCResponse response
                  && (response.error() != null    // We have a JSONRPCError.
                     || (response.result() != null   // Or we have a result that may hold and error (such as CallToolResult)
                           && response.result() instanceof McpSchema.CallToolResult callToolResult && callToolResult.isError()))) {
            outcome = AuditEvent.OUTCOME_FAILURE;
            if (response.error() != null) {
               errorCode = response.error().code();
            }
         }

         // Estimate the response content size from the CallToolResult text
         // content already in hand instead of re-serializing the whole result with Jackson.
         long responseSize = 0;
         if (result.isJSONRPCResponse() && result.message() instanceof McpSchema.JSONRPCResponse response
               && response.result() instanceof McpSchema.CallToolResult callToolResult) {
            if (callToolResult.content() != null) {
               for (McpSchema.Content content : callToolResult.content()) {
                  if (content instanceof McpSchema.TextContent textContent && textContent.text() != null) {
                     responseSize += textContent.text().length();
                  }
               }
            }
         }

         AuditEvent event = new AuditEvent(
               method, targetName, outcome, errorCode, durationMs,
               serviceName, serviceVersion, organizationId,
               requestId, sessionId, sourceIp, userId,
               responseSize, traceId
         );
         auditLogger.logMcpCall(event);
      });
   }

   @Nullable
   private SessionInfo getSessionInfo(HttpServerRequest serverRequest) {
      String sessionIdHeader = serverRequest.getHeader(McpSchema.HEADER_SESSION_ID);
      if (sessionIdHeader != null && !sessionIdHeader.isEmpty()) {
         return sessionStore.getSessionInfo(sessionIdHeader);
      }
      return null;
   }
}
