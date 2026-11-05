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

import io.reshapr.proxy.registry.ConfigurationEntry;

import jakarta.annotation.Nullable;

import java.util.List;

/**
 * Interface defining a builder for MCP Skills (Skills extension, {@code io.modelcontextprotocol/skills}).
 * @author laurent
 */
public interface McpSkillBuilder {

   /** URI scheme of the skill resources. */
   String SKILL_URI_SCHEME = "skill://";

   /**
    * Check whether this builder serves at least one skill.
    * @return true if skills are available
    */
   boolean hasSkills();

   /**
    * List the entries of all available skills.
    * @param configuration The MCP endpoint configuration entry if we need to access backend.
    * @return A list of skill entries
    */
   List<McpSchema.SkillEntry> listSkills(ConfigurationEntry configuration);

   /**
    * Get the entry of a single skill.
    * @param uri The URI of the skill's SKILL.md
    * @param configuration The MCP endpoint configuration entry if we need to access backend.
    * @return The skill entry, or {@code null} if no skill matches the URI
    */
   @Nullable
   McpSchema.SkillEntry getSkill(String uri, ConfigurationEntry configuration);

   /**
    * Read a file of a skill.
    * @param uri The URI of the skill file
    * @param configuration The MCP endpoint configuration entry if we need to access backend.
    * @return The file contents, or {@code null} if the URI is not a file of a served skill
    */
   @Nullable
   List<McpSchema.ResourceContents> readResource(String uri, ConfigurationEntry configuration);

   /**
    * List the direct children of a skill directory.
    * @param uri The URI of the directory
    * @param configuration The MCP endpoint configuration entry if we need to access backend.
    * @return The children resources, or {@code null} if the URI is not a directory of a served skill
    */
   @Nullable
   List<McpSchema.Resource> readDirectory(String uri, ConfigurationEntry configuration);
}
