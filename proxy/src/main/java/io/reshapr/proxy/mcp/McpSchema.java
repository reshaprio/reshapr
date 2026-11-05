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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Based on the <a href="http://www.jsonrpc.org/specification">JSON-RPC 2.0 specification</a> and the
 * <a href= "https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/schema/2025-06-18/schema.ts">Model Context
 * Protocol Schema</a>.
 * @author laurent
 */
public class McpSchema {

   /**
    * First MCP protocol version introducing the stateless mode (no session): the server no longer
    * issues an {@code MCP-Session-Id} and elicited secrets are bound to the authenticated user
    * instead of a session. Protocol versions strictly before this one keep the legacy session-based
    * behavior.
    */
   public static final String PROTOCOL_VERSION_STATELESS = "2026-07-28";

   public static final List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of(
         "2024-11-05",
         "2025-03-26",
         "2025-06-18",
         "2025-11-25",
         PROTOCOL_VERSION_STATELESS
   );

   public static final String JSONRPC_VERSION = "2.0";

   /**
    * Whether {@code protocolVersion} is greater than or equal to {@code reference}, using the declared
    * order of {@link #SUPPORTED_PROTOCOL_VERSIONS}. Unknown or {@code null} versions are treated as the
    * oldest (i.e. "not at least"), so callers safely fall back to the legacy behavior. Prefer this over a
    * strict {@code equals} so future protocol versions keep selecting the modern shape.
    */
   public static boolean isAtLeast(String protocolVersion, String reference) {
      if (protocolVersion == null || reference == null) {
         return false;
      }
      int versionIndex = SUPPORTED_PROTOCOL_VERSIONS.indexOf(protocolVersion);
      int referenceIndex = SUPPORTED_PROTOCOL_VERSIONS.indexOf(reference);
      return versionIndex >= 0 && versionIndex >= referenceIndex;
   }

   // ---------------------------
   // Http Header Names
   // ---------------------------

   public static final String HEADER_SESSION_ID = "MCP-Session-Id";
   public static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version";

   /** Modern (SEP-2243, {@code >= 2026-07-28}) request header mirroring the JSON-RPC body {@code method}. */
   public static final String HEADER_METHOD = "Mcp-Method";

   /**
    * Modern (SEP-2243, {@code >= 2026-07-28}) request header mirroring the JSON-RPC body target,
    * i.e. {@code params.name} (tools/call, prompts/get) or {@code params.uri} (resources/read).
    */
   public static final String HEADER_NAME = "Mcp-Name";

   /**
    * Key under a modern (stateless) request's {@code params._meta} carrying the negotiated protocol
    * version the request is framed with (SEP-2243).
    */
   public static final String META_KEY_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";

   // ---------------------------
   // Method Names
   // ---------------------------

   // Lifecycle Methods
   public static final String METHOD_INITIALIZE = "initialize";
   public static final String METHOD_NOTIFICATION_INITIALIZED = "notifications/initialized";
   public static final String METHOD_PING = "ping";
   public static final String METHOD_SERVER_DISCOVER = "server/discover";

   // Tool Methods
   public static final String METHOD_TOOLS_LIST = "tools/list";
   public static final String METHOD_TOOLS_CALL = "tools/call";
   public static final String METHOD_NOTIFICATION_TOOLS_LIST_CHANGED = "notifications/tools/list_changed";

   // Resources Methods
   public static final String METHOD_RESOURCES_LIST = "resources/list";
   public static final String METHOD_RESOURCES_READ = "resources/read";
   public static final String METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED = "notifications/resources/list_changed";
   public static final String METHOD_RESOURCES_TEMPLATES_LIST = "resources/templates/list";
   public static final String METHOD_RESOURCES_SUBSCRIBE = "resources/subscribe";
   public static final String METHOD_RESOURCES_UNSUBSCRIBE = "resources/unsubscribe";
   public static final String METHOD_RESOURCES_DIRECTORY_READ = "resources/directory/read";

   // Skills extension (SEP-2640).
   public static final String EXTENSION_SKILLS = "io.modelcontextprotocol/skills";
   public static final String METHOD_SKILLS_LIST = "skills/list";
   public static final String METHOD_SKILLS_GET = "skills/get";
   public static final String MIME_TYPE_DIRECTORY = "inode/directory";

   // Prompt Methods
   public static final String METHOD_PROMPTS_LIST = "prompts/list";
   public static final String METHOD_PROMPTS_GET = "prompts/get";
   public static final String METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED = "notifications/prompts/list_changed";

   // Logging Methods
   public static final String METHOD_LOGGING_SET_LEVEL = "logging/setLevel";
   public static final String METHOD_NOTIFICATION_MESSAGE = "notifications/message";

   // Roots Methods
   public static final String METHOD_ROOTS_LIST = "roots/list";
   public static final String METHOD_NOTIFICATION_ROOTS_LIST_CHANGED = "notifications/roots/list_changed";

   // Sampling Methods
   public static final String METHOD_SAMPLING_CREATE_MESSAGE = "sampling/createMessage";

   // Elicitation Methods
   public static final String METHOD_ELICITATION_CREATE = "elicitation/create";
   public static final String METHOD_ELICITATION_COMPLETE = "elicitation/complete";

   private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();


   // ---------------------------
   // JSON-RPC Error Codes
   // ---------------------------
   /**
    * Standard error codes used in MCP JSON-RPC responses.
    */
   public static final class ErrorCodes {

      /** Invalid JSON was received by the server. */
      public static final int PARSE_ERROR = -32700;

      /** The JSON sent is not a valid Request object. */
      public static final int INVALID_REQUEST = -32600;

      /** The method does not exist / is not available. */
      public static final int METHOD_NOT_FOUND = -32601;

      /** Invalid method parameter(s). */
      public static final int INVALID_PARAMS = -32602;

      /** Internal JSON-RPC error. */
      public static final int INTERNAL_ERROR = -32603;

      // Modern (SEP-2243, >= 2026-07-28) protocol error codes

      /**
       * A modern mirror header ({@code Mcp-Method}, {@code Mcp-Name} or {@code MCP-Protocol-Version})
       * disagrees with the request body. Mapped to HTTP 400 by the transport.
       */
      public static final int HEADER_MISMATCH = -32020;

      /**
       * A modern request declares a protocol version the server does not support. Mapped to HTTP 400 by
       * the transport; the error {@code data} names the supported versions.
       */
      public static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;

      /**
       * A modern request needs a client capability it did not declare (e.g. {@code elicitation} for an
       * {@code input_required} round trip). Mapped to HTTP 400 by the transport; the error {@code data}
       * names the required capabilities.
       */
      public static final int MISSING_CLIENT_CAPABILITY = -32021;

      // Implementation-specific JSON-RPC error codes [-32000, -32099]

      /** URL Elicitation is required */
      public static final int URL_ELICITATION_REQUIRED = -32042;
   }

   /** Base interface for MCP objects that include optional metadata in the `_meta` field. */
   public interface Meta {
      Map<String, Object> meta();
   }

   /** JSON-RPC Message Types. */
   public sealed interface JSONRPCMessage permits JSONRPCRequest, JSONRPCNotification, JSONRPCResponse {
      String jsonrpc();
   }

   // spotless:off
   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record JSONRPCRequest(
         @JsonProperty("jsonrpc") String jsonrpc,
         @JsonProperty("method") String method,
         @JsonProperty("id") Object id,
         @JsonProperty("params") Object params) implements JSONRPCMessage {
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record JSONRPCNotification(
         @JsonProperty("jsonrpc") String jsonrpc,
         @JsonProperty("method") String method,
         @JsonProperty("params") Map<String, Object> params) implements JSONRPCMessage {
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record JSONRPCResponse(
         @JsonProperty("jsonrpc") String jsonrpc,
         @JsonProperty("id") Object id,
         @JsonProperty("result") Object result,
         @JsonProperty("error") JSONRPCError error) implements JSONRPCMessage {

      @JsonInclude(JsonInclude.Include.NON_ABSENT)
      @JsonIgnoreProperties(ignoreUnknown = true)
      public record JSONRPCError(
            @JsonProperty("code") int code,
            @JsonProperty("message") String message,
            @JsonProperty("data") Object data) {
      }
   }
   // spotless:on

   // spotless:off
   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record Implementation(
         @JsonProperty("name") String name,
         @JsonProperty("version") String version) {
   }

   public enum Role {
      @JsonProperty("user") USER,
      @JsonProperty("assistant") ASSISTANT
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record ClientCapabilities(
         @JsonProperty("experimental") Map<String, Object> experimental,
         @JsonProperty("roots") RootCapabilities roots,
         @JsonProperty("sampling") Sampling sampling,
         @JsonProperty("elicitation") Elicitation elicitation) {

      public ClientCapabilities(Map<String, Object> experimental, RootCapabilities roots, Sampling sampling) {
         this(experimental, roots, sampling, null);
      }

      @JsonInclude(JsonInclude.Include.NON_ABSENT)
      @JsonIgnoreProperties(ignoreUnknown = true)
      public record RootCapabilities(
            @JsonProperty("listChanged") Boolean listChanged) {
      }

      @JsonInclude(JsonInclude.Include.NON_ABSENT)
      @JsonIgnoreProperties(ignoreUnknown = true)
      public record Sampling() {
      }

      @JsonInclude(JsonInclude.Include.NON_ABSENT)
      @JsonIgnoreProperties(ignoreUnknown = true)
      public record Elicitation() {
      }
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record ServerCapabilities(
         @JsonProperty("experimental") Map<String, Object> experimental,
         @JsonProperty("logging") LoggingCapabilities logging,
         @JsonProperty("prompts") PromptCapabilities prompts,
         @JsonProperty("resources") ResourceCapabilities resources,
         @JsonProperty("tools") ToolCapabilities tools,
         @JsonProperty("extensions") Map<String, Object> extensions) {

      public ServerCapabilities(Map<String, Object> experimental, LoggingCapabilities logging,
                                PromptCapabilities prompts, ResourceCapabilities resources, ToolCapabilities tools) {
         this(experimental, logging, prompts, resources, tools, null);
      }

      @JsonInclude(JsonInclude.Include.NON_ABSENT)
      public record LoggingCapabilities() {
      }

      @JsonInclude(JsonInclude.Include.NON_ABSENT)
      public record PromptCapabilities(
            @JsonProperty("listChanged") Boolean listChanged) {
      }

      @JsonInclude(JsonInclude.Include.NON_ABSENT)
      public record ResourceCapabilities(
            @JsonProperty("subscribe") Boolean subscribe,
            @JsonProperty("listChanged") Boolean listChanged) {
      }

      @JsonInclude(JsonInclude.Include.NON_ABSENT)
      public record ToolCapabilities(
            @JsonProperty("listChanged") Boolean listChanged) {
      }
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record ModelPreferences(
         @JsonProperty("hints") List<ModelHint> hints,
         @JsonProperty("costPriority") Double costPriority,
         @JsonProperty("speedPriority") Double speedPriority,
         @JsonProperty("intelligencePriority") Double intelligencePriority) {
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record ModelHint(@JsonProperty("name") String name) {
      public static ModelHint of(String name) {
         return new ModelHint(name);
      }
   }
   
   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record SamplingMessage(
         @JsonProperty("role") Role role,
         @JsonProperty("content") Content content) {
   }
   // spotless:on

   /** MCP Request Types. */
   public sealed interface Request permits InitializeRequest, SimpleRequest {

   }

   // spotless:off
   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record InitializeRequest(
         @JsonProperty("protocolVersion") String protocolVersion,
         @JsonProperty("capabilities") ClientCapabilities capabilities,
         @JsonProperty("clientInfo") Implementation clientInfo) implements Request {
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record InitializeResult(
        @JsonProperty("protocolVersion") String protocolVersion,
        @JsonProperty("capabilities") ServerCapabilities capabilities,
        @JsonProperty("serverInfo") Implementation serverInfo,
        @JsonProperty("instructions") String instructions) {
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record DiscoverResult(
         @JsonProperty("resultType") String resultType,
         @JsonProperty("supportedVersions") List<String> supportedVersions,
         @JsonProperty("capabilities") ServerCapabilities capabilities,
         // serverInfo is REQUIRED at the top level by the modern (2026-07-28) DiscoverResult wire schema.
         // Placing it only under _meta makes clients (e.g. @modelcontextprotocol/client) fail schema
         // validation and, in pin mode, reject the negotiation with
         // "did not offer pinned protocol version ... via server/discover".
         @JsonProperty("serverInfo") Implementation serverInfo,
         @JsonProperty("_meta") Map<String, Object> meta,
         @JsonProperty("instructions") String instructions,
         @JsonProperty("ttlMs") Long ttlMs,
         @JsonProperty("cacheScope") String cacheScope) implements Meta {

      public DiscoverResult(List<String> supportedVersions, ServerCapabilities capabilities,
            Implementation serverInfo, Map<String, Object> meta, Long ttlMs, String cacheScope) {
         this("complete", supportedVersions, capabilities, serverInfo, meta, null, ttlMs, cacheScope);
      }
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record SimpleRequest(
         @JsonProperty("name") String name,
         @JsonProperty("arguments") Map<String, Object> arguments) implements Request {
   }

   /**
    * Result of a {@code prompts/list} call. Like {@link ListToolsResult}, two wire shapes coexist
    * depending on the negotiated protocol version (Option B: one record per version):
    * <ul>
    *   <li>{@link Legacy} — protocol versions strictly before {@link #PROTOCOL_VERSION_STATELESS}: only
    *       {@code prompts} and the optional {@code nextCursor}.</li>
    *   <li>{@link Modern} — protocol versions {@code >= 2026-07-28}: adds the mandatory {@code resultType}
    *       discriminator plus the client-cache hints {@code ttlMs} and {@code cacheScope}.</li>
    * </ul>
    * The concrete shape is chosen by {@code McpProtocolDialect}, never by callers.
    */
   public sealed interface ListPromptsResult permits ListPromptsResult.Legacy, ListPromptsResult.Modern {
      List<Prompt> prompts();
      String nextCursor();

      @JsonInclude(JsonInclude.Include.NON_ABSENT)
      @JsonIgnoreProperties(ignoreUnknown = true)
      record Legacy(
            @JsonProperty("prompts") List<Prompt> prompts,
            @JsonProperty("nextCursor") String nextCursor) implements ListPromptsResult {
      }

      @JsonInclude(JsonInclude.Include.NON_ABSENT)
      @JsonIgnoreProperties(ignoreUnknown = true)
      record Modern(
            @JsonProperty("resultType") String resultType,
            @JsonProperty("prompts") List<Prompt> prompts,
            @JsonProperty("nextCursor") String nextCursor,
            @JsonProperty("ttlMs") Long ttlMs,
            @JsonProperty("cacheScope") String cacheScope) implements ListPromptsResult {
      }
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record GetPromptResult(
         @JsonProperty("description") String description,
         @JsonProperty("messages") List<PromptMessage> messages) {

      public GetPromptResult(List<PromptMessage> messages) {
         this(null, messages);
      }
   }

   /**
    * Result of a {@code resources/list} call. Like {@link ListToolsResult}, two wire shapes coexist
    * depending on the negotiated protocol version (Option B: one record per version):
    * <ul>
    *   <li>{@link Legacy} — protocol versions strictly before {@link #PROTOCOL_VERSION_STATELESS}: only
    *       {@code resources} and the optional {@code nextCursor}.</li>
    *   <li>{@link Modern} — protocol versions {@code >= 2026-07-28}: adds the mandatory {@code resultType}
    *       discriminator plus the client-cache hints {@code ttlMs} and {@code cacheScope}.</li>
    * </ul>
    * The concrete shape is chosen by {@code McpProtocolDialect}, never by callers.
    */
   public sealed interface ListResourcesResult permits ListResourcesResult.Legacy, ListResourcesResult.Modern {
      List<Resource> resources();
      String nextCursor();

      @JsonInclude(JsonInclude.Include.NON_ABSENT)
      @JsonIgnoreProperties(ignoreUnknown = true)
      record Legacy(
            @JsonProperty("resources") List<Resource> resources,
            @JsonProperty("nextCursor") String nextCursor) implements ListResourcesResult {
      }

      @JsonInclude(JsonInclude.Include.NON_ABSENT)
      @JsonIgnoreProperties(ignoreUnknown = true)
      record Modern(
            @JsonProperty("resultType") String resultType,
            @JsonProperty("resources") List<Resource> resources,
            @JsonProperty("nextCursor") String nextCursor,
            @JsonProperty("ttlMs") Long ttlMs,
            @JsonProperty("cacheScope") String cacheScope) implements ListResourcesResult {
      }
   }

   /**
    * Result of a {@code resources/templates/list} call. Like {@link ListToolsResult}, two wire shapes coexist
    * depending on the negotiated protocol version (Option B: one record per version):
    * <ul>
    *   <li>{@link Legacy} — protocol versions strictly before {@link #PROTOCOL_VERSION_STATELESS}: only
    *       {@code resourceTemplates} and the optional {@code nextCursor}.</li>
    *   <li>{@link Modern} — protocol versions {@code >= 2026-07-28}: adds the mandatory {@code resultType}
    *       discriminator plus the client-cache hints {@code ttlMs} and {@code cacheScope}.</li>
    * </ul>
    * The concrete shape is chosen by {@code McpProtocolDialect}, never by callers.
    */
   public sealed interface ListResourceTemplatesResult
         permits ListResourceTemplatesResult.Legacy, ListResourceTemplatesResult.Modern {
      List<ResourceTemplate> resourceTemplates();
      String nextCursor();

      @JsonInclude(JsonInclude.Include.NON_ABSENT)
      @JsonIgnoreProperties(ignoreUnknown = true)
      record Legacy(
            @JsonProperty("resourceTemplates") List<ResourceTemplate> resourceTemplates,
            @JsonProperty("nextCursor") String nextCursor) implements ListResourceTemplatesResult {
      }

      @JsonInclude(JsonInclude.Include.NON_ABSENT)
      @JsonIgnoreProperties(ignoreUnknown = true)
      record Modern(
            @JsonProperty("resultType") String resultType,
            @JsonProperty("resourceTemplates") List<ResourceTemplate> resourceTemplates,
            @JsonProperty("nextCursor") String nextCursor,
            @JsonProperty("ttlMs") Long ttlMs,
            @JsonProperty("cacheScope") String cacheScope) implements ListResourceTemplatesResult {
      }
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record ReadResourceRequest(
         @JsonProperty("uri") String uri) {
   }

   /**
    * Result of a {@code resources/read} call. Two wire shapes coexist depending on the negotiated protocol
    * version, hence the sealed hierarchy:
    * <ul>
    *   <li>{@link Legacy} — protocol versions strictly before {@link #PROTOCOL_VERSION_STATELESS}: only
    *       {@code contents}.</li>
    *   <li>{@link Modern} — protocol versions {@code >= 2026-07-28}: adds the mandatory {@code resultType}
    *       discriminator plus the client-cache hints {@code ttlMs} and {@code cacheScope}.</li>
    * </ul>
    * The concrete shape is chosen by {@code McpProtocolDialect}, never by callers.
    */
   public sealed interface ReadResourceResult permits ReadResourceResult.Legacy, ReadResourceResult.Modern {
      List<ResourceContents> contents();

      @JsonInclude(JsonInclude.Include.NON_ABSENT)
      @JsonIgnoreProperties(ignoreUnknown = true)
      record Legacy(
            @JsonProperty("contents") List<ResourceContents> contents) implements ReadResourceResult {
      }

      @JsonInclude(JsonInclude.Include.NON_ABSENT)
      @JsonIgnoreProperties(ignoreUnknown = true)
      record Modern(
            @JsonProperty("resultType") String resultType,
            @JsonProperty("contents") List<ResourceContents> contents,
            @JsonProperty("ttlMs") Long ttlMs,
            @JsonProperty("cacheScope") String cacheScope) implements ReadResourceResult {
      }
   }

   /** One file of a skill, with its SHA-256 digest ({@code sha256:<hex>}) and raw size in bytes. */
   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   public record SkillFile(
         @JsonProperty("uri") String uri,
         @JsonProperty("digest") String digest,
         @JsonProperty("size") long size) {
   }

   /** A skill entry as returned by {@code skills/list} and {@code skills/get}. */
   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   public record SkillEntry(
         @JsonProperty("uri") String uri,
         @JsonProperty("frontmatter") Map<String, Object> frontmatter,
         @JsonProperty("resources") List<SkillFile> resources) {
   }

   /**
    * Result of {@code skills/list}. {@code resultType}, {@code ttlMs} and {@code cacheScope} are only set
    * for protocol versions {@code >= 2026-07-28} and omitted otherwise.
    */
   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   public record ListSkillsResult(
         @JsonProperty("resultType") String resultType,
         @JsonProperty("skills") List<SkillEntry> skills,
         @JsonProperty("nextCursor") String nextCursor,
         @JsonProperty("ttlMs") Long ttlMs,
         @JsonProperty("cacheScope") String cacheScope) {
   }

   /** Result of {@code skills/get}; {@code resultType} is only set for modern protocol versions. */
   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   public record GetSkillResult(
         @JsonProperty("resultType") String resultType,
         @JsonProperty("skill") SkillEntry skill) {
   }

   /** Result of {@code resources/directory/read}; {@code resultType} is only set for modern protocol versions. */
   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   public record ReadDirectoryResult(
         @JsonProperty("resultType") String resultType,
         @JsonProperty("resources") List<Resource> resources,
         @JsonProperty("nextCursor") String nextCursor) {
   }

   /**
    * Result of a {@code tools/list} call. Two wire shapes coexist depending on the negotiated protocol
    * version, hence the sealed hierarchy (Option B: one record per version):
    * <ul>
    *   <li>{@link Legacy} — protocol versions strictly before {@link #PROTOCOL_VERSION_STATELESS}: only
    *       {@code tools} and the optional {@code nextCursor}.</li>
    *   <li>{@link Modern} — protocol versions {@code >= 2026-07-28}: adds the mandatory {@code resultType}
    *       discriminator plus the client-cache hints {@code ttlMs} and {@code cacheScope}.</li>
    * </ul>
    * The concrete shape is chosen by {@code McpProtocolDialect}, never by callers.
    */
   public sealed interface ListToolsResult permits ListToolsResult.Legacy, ListToolsResult.Modern {
      List<Tool> tools();
      String nextCursor();

      @JsonInclude(JsonInclude.Include.NON_ABSENT)
      @JsonIgnoreProperties(ignoreUnknown = true)
      record Legacy(
            @JsonProperty("tools") List<Tool> tools,
            @JsonProperty("nextCursor") String nextCursor) implements ListToolsResult {
      }

      @JsonInclude(JsonInclude.Include.NON_ABSENT)
      @JsonIgnoreProperties(ignoreUnknown = true)
      record Modern(
            @JsonProperty("resultType") String resultType,
            @JsonProperty("tools") List<Tool> tools,
            @JsonProperty("nextCursor") String nextCursor,
            @JsonProperty("ttlMs") Long ttlMs,
            @JsonProperty("cacheScope") String cacheScope) implements ListToolsResult {
      }
   }

   /**
    * Result of a {@code tools/call} invocation. Like {@link ListToolsResult}, two wire shapes coexist
    * depending on the negotiated protocol version (Option B: one record per version):
    * <ul>
    *   <li>{@link Legacy} — protocol versions strictly before {@link #PROTOCOL_VERSION_STATELESS}: only the
    *       unstructured {@code content} and the optional {@code isError} flag.</li>
    *   <li>{@link Modern} — protocol versions {@code >= 2026-07-28}: adds the optional
    *       {@code structuredContent} object and the base-{@code Result} {@code _meta} field.</li>
    * </ul>
    * The concrete shape is chosen by {@code McpProtocolDialect}, never by callers.
    */
   public sealed interface CallToolResult permits CallToolResult.Legacy, CallToolResult.Modern {
      List<Content> content();
      Boolean isError();

      @JsonInclude(JsonInclude.Include.NON_ABSENT)
      @JsonIgnoreProperties(ignoreUnknown = true)
      record Legacy(
            @JsonProperty("content") List<Content> content,
            @JsonProperty("isError") Boolean isError) implements CallToolResult {
      }

      @JsonInclude(JsonInclude.Include.NON_ABSENT)
      @JsonIgnoreProperties(ignoreUnknown = true)
      record Modern(
            @JsonProperty("resultType") String resultType,
            @JsonProperty("content") List<Content> content,
            @JsonProperty("isError") Boolean isError) implements CallToolResult {
      }
   }
   // spotless:on

   /** Prompts model. */
   // spotless:off
   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record Prompt(
         @JsonProperty("name") String name,
         @JsonProperty("title") String title,
         @JsonProperty("description") String description,
         @JsonProperty("arguments") List<PromptArgument> arguments) {
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record PromptArgument(
         @JsonProperty("name") String name,
         @JsonProperty("title") String title,
         @JsonProperty("description") String description,
         @JsonProperty("required") Boolean required) {

      public PromptArgument(String name, String description, Boolean required) {
         this(name, null, description, required);
      }
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record PromptMessage(
         @JsonProperty("role") Role role,
         @JsonProperty("content") Content content) {
   }
   // spotless:on

   /** Elicitation model. */
   // spotless:off
   public enum ElicitationMode {
      @JsonProperty("for") FOR,
      @JsonProperty("url") URL
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record URLElicitation(
         @JsonProperty("mode") ElicitationMode mode,
         @JsonProperty("elicitationId") String elicitationId,
         @JsonProperty("url") String url,
         @JsonProperty("message") String message) {

      public URLElicitation(String elicitationId, String url, String message) {
         this(ElicitationMode.URL, elicitationId, url, message);
      }
   }

   public static JSONRPCRequest buildElicitationCreateRequest(URLElicitation elicitation) {
      return new JSONRPCRequest(JSONRPC_VERSION, METHOD_ELICITATION_CREATE,
            elicitation.elicitationId,
            elicitation);
   }

   public static JSONRPCResponse.JSONRPCError buildURLElicitationRequiredError(List<URLElicitation> elicitations) {
      return new JSONRPCResponse.JSONRPCError(
            ErrorCodes.URL_ELICITATION_REQUIRED,
            "This request requires more information.",
            Map.of("elicitations", elicitations)
      );
   }
   // spotless:on

   /** Resources model. */
   // spotless:off
   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record Annotations(
         @JsonProperty("audience") List<Role> audience,
         @JsonProperty("priority") Double priority,
         @JsonProperty("lastModified") String lastModified) {

      public Annotations(List<Role> audience, Double priority) {
         this(audience, priority, null);
      }
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record Resource(
         @JsonProperty("uri") String uri,
         @JsonProperty("name") String name,
         @JsonProperty("title") String title,
         @JsonProperty("description") String description,
         @JsonProperty("mimeType") String mimeType,
         @JsonProperty("size") Long size,
         @JsonProperty("annotations") Annotations annotations,
         @JsonProperty("_meta") Map<String, Object> meta) implements Meta {

      public Resource(String uri, String name, String title,String description, String mimeType, Long size) {
         this(uri, name, title, description, mimeType, size, null, null);
      }

      public Resource(String uri, String name, String title,String description, String mimeType, Long size, Annotations annotations) {
         this(uri, name, title, description, mimeType, size, annotations, null);
      }
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record ResourceTemplate(
         @JsonProperty("uriTemplate") String uri,
         @JsonProperty("name") String name,
         @JsonProperty("title") String title,
         @JsonProperty("description") String description,
         @JsonProperty("mimeType") String mimeType,
         @JsonProperty("annotations") Annotations annotations) {

      public ResourceTemplate(String uri, String name, String title,String description, String mimeType) {
         this(uri, name, title, description, mimeType, null);
      }
   }

   /** Tools model. */
   // spotless:off
   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record Tool(
         @JsonProperty("name") String name,
         @JsonProperty("description") String description,
         @JsonProperty("inputSchema") JsonSchema inputSchema,
         @JsonProperty("_meta") Map<String, Object> meta) implements Meta {

      public Tool(String name, String description, String schema) {
         this(name, description, parseSchema(schema), null);
      }
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record JsonSchema(
         @JsonProperty("type") String type,
         @JsonProperty("properties") Map<String, Object> properties,
         @JsonProperty("required") List<String> required,
         @JsonProperty("additionalProperties") Boolean additionalProperties) {
   }

   private static JsonSchema parseSchema(String schema) {
      try {
         return OBJECT_MAPPER.readValue(schema, JsonSchema.class);
      }
      catch (IOException e) {
         throw new IllegalArgumentException("Invalid schema: " + schema, e);
      }
   }
   // spotless:on

   /** Result Content Types. */
   @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
   @JsonSubTypes({ @JsonSubTypes.Type(value = TextContent.class, name = "text"),
         @JsonSubTypes.Type(value = ImageContent.class, name = "image"),
         @JsonSubTypes.Type(value = EmbeddedResource.class, name = "resource") })
   public sealed interface Content permits TextContent, ImageContent, EmbeddedResource {

      default String type() {
         if (this instanceof TextContent) {
            return "text";
         } else if (this instanceof ImageContent) {
            return "image";
         } else if (this instanceof EmbeddedResource) {
            return "resource";
         }
         throw new IllegalArgumentException("Unknown content type: " + this);
      }
   }

   // spotless:off
   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record TextContent(
         @JsonProperty("audience") List<Role> audience,
         @JsonProperty("priority") Double priority,
         @JsonProperty("text") String text) implements Content {

      public TextContent(String content) {
         this(null, null, content);
      }
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record ImageContent(
         @JsonProperty("audience") List<Role> audience,
         @JsonProperty("priority") Double priority,
         @JsonProperty("data") String data,
         @JsonProperty("mimeType") String mimeType) implements Content {
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record EmbeddedResource(
        @JsonProperty("audience") List<Role> audience,
        @JsonProperty("priority") Double priority,
        @JsonProperty("resource") ResourceContents resource) implements Content {
   }

   @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION, include = JsonTypeInfo.As.PROPERTY)
   @JsonSubTypes({ @JsonSubTypes.Type(value = TextResourceContents.class, name = "text"),
         @JsonSubTypes.Type(value = BlobResourceContents.class, name = "blob") })
   public sealed interface ResourceContents permits TextResourceContents, BlobResourceContents {
      String uri();
      String mimeType();
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record TextResourceContents(
         @JsonProperty("uri") String uri,
         @JsonProperty("mimeType") String mimeType,
         @JsonProperty("text") String text,
         @JsonProperty("_meta") Map<String, Object> meta) implements ResourceContents, Meta {

      public TextResourceContents(String uri, String mimeType, String text) {
         this(uri, mimeType, text, null);
      }
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record BlobResourceContents(
         @JsonProperty("uri") String uri,
         @JsonProperty("mimeType") String mimeType,
         @JsonProperty("blob") String blob) implements ResourceContents {
   }
   // spotless:on


   // MCP 2026-07-28 version ony additions ---------------------------------

   // spotless:off
   /**
    * A single entry of {@link InputRequiredResult#inputRequests}. Per the MCP draft schema it carries
    * <b>only</b> the {@code method} and {@code params} of the request to perform (not a full JSON-RPC
    * envelope: no {@code jsonrpc} version, no {@code id} — the id is the map key in {@code inputRequests}).
    */
   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record InputRequest(
         @JsonProperty("method") String method,
         @JsonProperty("params") Object params) {
   }

   /**
    * An InputRequiredResult sent by the server to indicate that additional input is needed before the request can be completed.
    * ({@code >= 2026-07-28} addition called Multi Round-Trip interactions - typically used to carry the
    * {@code elicitation/create} ("URL Mode") server-to-client requests.)
    * <p>
    * It extends the base {@code Result} (hence the optional {@code _meta}). The mandatory {@code resultType}
    * discriminator is fixed to {@value #RESULT_TYPE_INPUT_REQUIRED}. {@code inputRequests} is a
    * <b>map keyed by request id</b> (here the elicitation id) whose values only hold {@code method} and
    * {@code params} (see {@link InputRequest}). The {@code requestState} is an <b>opaque, server-generated
    * token</b> that becomes <b>mandatory in stateless mode</b> (no session) when a URL Mode elicitation drives
    * an OAuth flow: the client MUST echo it back when resuming the paused request so that a stateless server
    * can correlate the resumed call without server-side session state.
    */
   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record InputRequiredResult(
         @JsonProperty("resultType") String resultType,
         @JsonProperty("inputRequests") Map<String, InputRequest> inputRequests,
         @JsonProperty("requestState") String requestState,
         @JsonProperty("_meta") Map<String, Object> meta) implements Meta {
   }

   /** The {@code resultType} discriminator value carried by an {@link InputRequiredResult}. */
   public static final String RESULT_TYPE_INPUT_REQUIRED = "input_required";

   /**
    * Build an {@link InputRequiredResult} holding one {@code elicitation/create} input request per elicitation,
    * keyed by elicitation id, and carrying the opaque {@code requestState} the client must return to resume
    * the paused request.
    * @param elicitations The URL Mode elicitations to request from the client.
    * @param requestState The opaque resume token (mandatory for stateless OAuth URL Mode), or {@code null}.
    */
   public static InputRequiredResult buildInputRequiredResult(List<URLElicitation> elicitations, String requestState) {
      Map<String, InputRequest> inputRequests = new LinkedHashMap<>();
      for (URLElicitation elicitation : elicitations) {
         inputRequests.put(elicitation.elicitationId(), new InputRequest(METHOD_ELICITATION_CREATE, elicitation));
      }
      return new InputRequiredResult(RESULT_TYPE_INPUT_REQUIRED, inputRequests, requestState, null);
   }
   // spotless:on
}
