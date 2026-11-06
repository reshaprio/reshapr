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

import io.reshapr.json.ObjectMapperFactory;
import io.reshapr.proxy.proxy.BackendResponse;
import io.reshapr.proxy.proxy.ProxyService;
import io.reshapr.proxy.registry.ArtifactEntry;
import io.reshapr.proxy.registry.ArtifactEntryType;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.ServiceEntry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.net.HttpHeaders;
import jakarta.annotation.Nullable;
import org.jboss.logging.Logger;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * An implementation of McpSkillBuilder that serves the Agent Skills declared in Reshapr Skills artifacts through the MCP Skills extension
 * ({@code io.modelcontextprotocol/skills}, SEP-2640). Like MCP Apps {@code ui://} resources, skill directories
 * are hosted on an HTTP(S) server designated by the {@code remoteContent} property: the gateway fetches the
 * files and converts them into {@code skill://} resources, {@code skills/list} / {@code skills/get} entries
 * (with SHA-256 digests) and {@code resources/directory/read} listings.
 * @author laurent
 */
public class ReshaprSkillsMcpSkillBuilder implements McpSkillBuilder {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   static final String SKILL_FILE_NAME = "SKILL.md";

   /** Limits fixed by the extension for every skill. */
   static final int MAX_RESOURCES_PER_SKILL = 512;
   static final long MAX_SKILL_SIZE = 16L * 1024 * 1024;

   private static final String DEFS_CACHE_KEY = "rsp-defs";
   private static final String LOADED_CACHE_KEY_PREFIX = "rsp-loaded-";

   private static final ObjectMapper YAML_MAPPER = ObjectMapperFactory.getYamlObjectMapper();

   private final ServiceEntry service;
   private final List<ArtifactEntry> attachedArtifacts;
   private final WorkCache workCache;
   private final ProxyService proxyService;

   public ReshaprSkillsMcpSkillBuilder(ServiceEntry service, List<ArtifactEntry> attachedArtifacts,
                                       WorkCache workCache, ProxyService proxyService) {
      this.service = service;
      this.attachedArtifacts = attachedArtifacts;
      this.workCache = workCache;
      this.proxyService = proxyService;
   }

   /** A skill as declared in an artifact. */
   record SkillDefinition(String name, String root, String remoteContent, List<String> files,
                          @Nullable Map<String, Object> meta) {

      String skillFileUri() {
         return root + "/" + SKILL_FILE_NAME;
      }

      List<String> allFiles() {
         List<String> all = new ArrayList<>();
         all.add(SKILL_FILE_NAME);
         all.addAll(files);
         return all;
      }

      String remoteUrl(String relativePath) {
         return (remoteContent.endsWith("/") ? remoteContent : remoteContent + "/") + relativePath;
      }
   }

   /** A skill whose files have been fetched once to compute its manifest. */
   record LoadedSkill(SkillDefinition definition, McpSchema.SkillEntry entry, Map<String, Long> sizes,
                      Map<String, String> mimeTypes, long loadedAt) {
   }

   @Override
   public boolean hasSkills() {
      return !getDefinitions().isEmpty();
   }

   /** Skills that cannot be loaded are skipped. */
   @Override
   public List<McpSchema.SkillEntry> listSkills(ConfigurationEntry configuration) {
      List<McpSchema.SkillEntry> entries = new ArrayList<>();
      for (SkillDefinition definition : getDefinitions().values()) {
         LoadedSkill loaded = load(definition, configuration);
         if (loaded != null) {
            entries.add(loaded.entry());
         }
      }
      return entries;
   }

   @Override
   @Nullable
   public McpSchema.SkillEntry getSkill(String uri, ConfigurationEntry configuration) {
      SkillDefinition definition = getDefinitions().get(uri);
      if (definition == null) {
         return null;
      }
      LoadedSkill loaded = load(definition, configuration);
      return loaded != null ? loaded.entry() : null;
   }

   @Override
   @Nullable
   public List<McpSchema.ResourceContents> readResource(String uri, ConfigurationEntry configuration) {
      if (!uri.startsWith(SKILL_URI_SCHEME)) {
         return null;
      }
      SkillDefinition definition = findOwner(uri);
      if (definition == null || uri.length() <= definition.root().length() + 1) {
         return null;
      }
      String relativePath = uri.substring(definition.root().length() + 1);
      if (!definition.allFiles().contains(relativePath)) {
         return null;
      }

      BackendResponse response = fetch(definition, relativePath, configuration);
      if (response == null) {
         return null;
      }
      String mimeType = mimeTypeFor(relativePath, response);
      Map<String, Object> meta = SKILL_FILE_NAME.equals(relativePath) ? definition.meta() : null;
      if (isText(mimeType)) {
         return List.of(new McpSchema.TextResourceContents(uri, mimeType,
               new String(response.content(), StandardCharsets.UTF_8), meta));
      }
      return List.of(new McpSchema.BlobResourceContents(uri, mimeType,
            Base64.getEncoder().encodeToString(response.content())));
   }

   @Override
   @Nullable
   public List<McpSchema.Resource> readDirectory(String uri, ConfigurationEntry configuration) {
      SkillDefinition definition = findOwner(uri);
      if (definition == null) {
         return null;
      }
      String directory = uri.equals(definition.root()) ? "" : uri.substring(definition.root().length() + 1);

      Map<String, McpSchema.Resource> children = new TreeMap<>();
      for (String file : definition.allFiles()) {
         String remaining;
         if (directory.isEmpty()) {
            remaining = file;
         } else if (file.startsWith(directory + "/")) {
            remaining = file.substring(directory.length() + 1);
         } else {
            continue;
         }
         int slash = remaining.indexOf('/');
         if (slash < 0) {
            Long size = null;
            LoadedSkill loaded = load(definition, configuration);
            if (loaded != null) {
               size = loaded.sizes().get(file);
            }
            children.put(remaining, new McpSchema.Resource(uri + "/" + remaining, remaining, null, null,
                  loaded != null ? loaded.mimeTypes().get(file) : mimeTypeFor(file, null), size));
         } else {
            String subDirectory = remaining.substring(0, slash);
            children.putIfAbsent(subDirectory, new McpSchema.Resource(uri + "/" + subDirectory, subDirectory,
                  null, null, McpSchema.MIME_TYPE_DIRECTORY, null));
         }
      }
      return children.isEmpty() ? null : new ArrayList<>(children.values());
   }

   /** Skill definitions merged across all attached Skills artifacts, keyed by SKILL.md URI. */
   @SuppressWarnings("unchecked")
   private Map<String, SkillDefinition> getDefinitions() {
      Map<String, SkillDefinition> merged = new LinkedHashMap<>();
      if (attachedArtifacts == null) {
         return merged;
      }
      for (ArtifactEntry artifact : attachedArtifacts) {
         if (!ArtifactEntryType.RESHAPR_SKILLS.equals(artifact.type())) {
            continue;
         }
         Object cached = workCache.get(artifact.id(), DEFS_CACHE_KEY);
         List<SkillDefinition> definitions;
         if (cached instanceof List<?> list) {
            definitions = (List<SkillDefinition>) list;
         } else {
            definitions = parseDefinitions(artifact);
            workCache.set(artifact.id(), DEFS_CACHE_KEY, definitions);
         }
         // First declaring artifact wins on collision.
         for (SkillDefinition definition : definitions) {
            merged.putIfAbsent(definition.skillFileUri(), definition);
         }
      }
      return merged;
   }

   private List<SkillDefinition> parseDefinitions(ArtifactEntry artifact) {
      List<SkillDefinition> definitions = new ArrayList<>();
      try {
         JsonNode skillsNode = YAML_MAPPER.readTree(artifact.content()).get("skills");
         if (skillsNode == null || !skillsNode.isObject()) {
            return definitions;
         }
         Iterator<String> names = skillsNode.fieldNames();
         while (names.hasNext()) {
            String name = names.next();
            JsonNode skillNode = skillsNode.get(name);
            String remoteContent = skillNode.path("remoteContent").asText(null);
            if (remoteContent == null) {
               continue;
            }
            String path = skillNode.path("path").asText("");
            Set<String> files = new LinkedHashSet<>();
            if (skillNode.path("files").isArray()) {
               skillNode.get("files").forEach(file -> files.add(file.asText()));
            }
            files.remove(SKILL_FILE_NAME);
            Map<String, Object> meta = skillNode.has("_meta")
                  ? YAML_MAPPER.convertValue(skillNode.get("_meta"), new TypeReference<Map<String, Object>>() {}) : null;
            String root = SKILL_URI_SCHEME + (path.isEmpty() ? "" : path + "/") + name;
            definitions.add(new SkillDefinition(name, root, remoteContent, List.copyOf(files), meta));
         }
      } catch (Exception e) {
         logger.errorf(e, "Cannot read Reshapr Skills artifact '%s' for service '%s'", artifact.id(), service.id());
      }
      return definitions;
   }

   /** Find the skill whose directory contains the given URI (the longest root wins for nested skills). */
   @Nullable
   private SkillDefinition findOwner(String uri) {
      SkillDefinition owner = null;
      for (SkillDefinition definition : getDefinitions().values()) {
         String root = definition.root();
         if ((uri.equals(root) || uri.startsWith(root + "/"))
               && (owner == null || root.length() > owner.root().length())) {
            owner = definition;
         }
      }
      return owner;
   }

   /** Load (or get from cache while still fresh) the manifest of a skill by fetching all its files. */
   @Nullable
   private LoadedSkill load(SkillDefinition definition, ConfigurationEntry configuration) {
      String cacheKey = LOADED_CACHE_KEY_PREFIX + definition.root();
      long ttlMs = configuration != null && configuration.cachePolicy() != null
            ? configuration.cachePolicy().effectiveTtlMs() : ConfigurationEntry.CachePolicyEntry.DEFAULT_TTL_MS;
      if (workCache.get(service.id(), cacheKey) instanceof LoadedSkill cached
            && System.currentTimeMillis() - cached.loadedAt() < ttlMs) {
         return cached;
      }

      List<McpSchema.SkillFile> files = new ArrayList<>();
      Map<String, Long> sizes = new LinkedHashMap<>();
      Map<String, String> mimeTypes = new LinkedHashMap<>();
      Map<String, Object> frontmatter = null;
      long totalSize = 0;

      for (String relativePath : definition.allFiles()) {
         BackendResponse response = fetch(definition, relativePath, configuration);
         if (response == null) {
            logger.warnf("Skill '%s' of service '%s' is skipped: cannot fetch '%s' from '%s'", definition.name(),
                  service.id(), relativePath, definition.remoteUrl(relativePath));
            return null;
         }
         byte[] content = response.content() != null ? response.content() : new byte[0];
         totalSize += content.length;
         files.add(new McpSchema.SkillFile(definition.root() + "/" + relativePath, "sha256:" + sha256(content),
               content.length));
         sizes.put(relativePath, (long) content.length);
         mimeTypes.put(relativePath, mimeTypeFor(relativePath, response));

         if (SKILL_FILE_NAME.equals(relativePath)) {
            frontmatter = parseFrontmatter(new String(content, StandardCharsets.UTF_8));
            if (frontmatter == null || !definition.name().equals(frontmatter.get("name"))
                  || !(frontmatter.get("description") instanceof String)) {
               logger.warnf("Skill '%s' of service '%s' is skipped: SKILL.md frontmatter must declare "
                     + "'name' (equal to '%s') and 'description'", definition.name(), service.id(), definition.name());
               return null;
            }
         }
      }
      if (files.size() > MAX_RESOURCES_PER_SKILL || totalSize > MAX_SKILL_SIZE) {
         logger.warnf("Skill '%s' of service '%s' exceeds the Skills extension limits (%d files, %d bytes)",
               definition.name(), service.id(), files.size(), totalSize);
      }

      LoadedSkill loaded = new LoadedSkill(definition,
            new McpSchema.SkillEntry(definition.skillFileUri(), frontmatter, files), sizes, mimeTypes,
            System.currentTimeMillis());
      workCache.set(service.id(), cacheKey, loaded);
      return loaded;
   }

   @Nullable
   private BackendResponse fetch(SkillDefinition definition, String relativePath, ConfigurationEntry configuration) {
      try {
         BackendResponse response = proxyService.callBackend(configuration,
               URI.create(definition.remoteUrl(relativePath)), "GET", Collections.emptyMap(), null);
         if (response == null || response.status() < 200 || response.status() >= 300) {
            logger.warnf("Skill '%s' of service '%s': remote file '%s' returned HTTP status %s",
                  definition.name(), service.id(), definition.remoteUrl(relativePath),
                  response != null ? response.status() : "no response");
            return null;
         }
         return response;
      } catch (Exception e) {
         logger.warnf(e, "Skill '%s' of service '%s': cannot fetch remote file '%s'", definition.name(),
               service.id(), definition.remoteUrl(relativePath));
         return null;
      }
   }

   /** Parse the YAML frontmatter of a SKILL.md, or return null if absent or invalid. */
   @Nullable
   static Map<String, Object> parseFrontmatter(String skillMarkdown) {
      String text = skillMarkdown.startsWith("\uFEFF") ? skillMarkdown.substring(1) : skillMarkdown;
      String[] lines = text.split("\r?\n", -1);
      if (lines.length < 3 || !lines[0].trim().equals("---")) {
         return null;
      }
      StringBuilder yaml = new StringBuilder();
      for (int i = 1; i < lines.length; i++) {
         if (lines[i].trim().equals("---")) {
            try {
               JsonNode node = YAML_MAPPER.readTree(yaml.toString());
               return node != null && node.isObject()
                     ? YAML_MAPPER.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {}) : null;
            } catch (Exception e) {
               return null;
            }
         }
         yaml.append(lines[i]).append('\n');
      }
      return null;
   }

   static String mimeTypeFor(String path, @Nullable BackendResponse response) {
      String lower = path.toLowerCase();
      int dot = lower.lastIndexOf('.');
      String extension = dot >= 0 ? lower.substring(dot + 1) : "";
      switch (extension) {
         case "md", "markdown":
            return "text/markdown";
         case "txt":
            return "text/plain";
         case "json":
            return "application/json";
         case "yaml", "yml":
            return "application/yaml";
         case "xml":
            return "application/xml";
         case "html", "htm":
            return "text/html";
         case "csv":
            return "text/csv";
         case "py":
            return "text/x-python";
         case "js", "mjs":
            return "text/javascript";
         case "ts":
            return "text/typescript";
         case "sh":
            return "text/x-shellscript";
         default:
            break;
      }
      if (response != null && response.headers() != null) {
         for (Map.Entry<String, List<String>> header : response.headers().entrySet()) {
            if (HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(header.getKey()) && !header.getValue().isEmpty()) {
               return header.getValue().get(0).split(";")[0].trim();
            }
         }
      }
      return "application/octet-stream";
   }

   private static boolean isText(String mimeType) {
      return mimeType.startsWith("text/") || mimeType.contains("json") || mimeType.contains("yaml")
            || mimeType.contains("xml");
   }

   private static String sha256(byte[] content) {
      try {
         return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
      } catch (NoSuchAlgorithmException e) {
         throw new IllegalStateException(e);
      }
   }
}
