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
package io.reshapr.ctrl.service;

import io.reshapr.ctrl.model.ConfigurationTemplate;
import io.reshapr.ctrl.repository.ConfigurationTemplateRepository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Service for managing configuration templates in the Reshapr control plane.
 *
 * @author vaishnav
 */
@ApplicationScoped
public class ConfigurationTemplateManagerService {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final ConfigurationTemplateRepository templateRepository;

   public ConfigurationTemplateManagerService(ConfigurationTemplateRepository templateRepository) {
      this.templateRepository = templateRepository;
   }

   /**
    * Returns all configuration templates for the current tenant.
    * @return a list of templates
    */
   public List<ConfigurationTemplate> getConfigurationTemplates() {
      logger.debug("Retrieving all configuration templates");
      return templateRepository.findAll().list();
   }

   /**
    * Returns a single template by ID, or {@code null} if not found.
    * @param id the template ID
    * @return the template or null
    */
   public ConfigurationTemplate getConfigurationTemplate(String id) {
      return templateRepository.findById(id);
   }

   /**
    * Creates a new configuration template.
    * @param template the template to persist
    * @return the persisted template
    * @throws IllegalArgumentException if a template with the same name already exists for the tenant
    */
   @Transactional
   public ConfigurationTemplate createConfigurationTemplate(ConfigurationTemplate template) {
      logger.debugf("Creating configuration template with name '%s'", template.name);

      if (templateRepository.findByName(template.name).isPresent()) {
         logger.errorf("A configuration template named '%s' already exists", template.name);
         throw new IllegalArgumentException("A configuration template named '" + template.name + "' already exists");
      }

      templateRepository.persistAndFlush(template);
      return template;
   }

   /**
    * Updates an existing configuration template.
    * @param id the ID of the template to update
    * @param incoming the new values for the template
    * @return the updated template
    * @throws IllegalArgumentException if the template is not found or the new name conflicts
    */
   @Transactional
   public ConfigurationTemplate updateConfigurationTemplate(String id, ConfigurationTemplate incoming) {
      logger.debugf("Updating configuration template with id '%s'", id);

      ConfigurationTemplate existing = templateRepository.findById(id);
      if (existing == null) {
         logger.errorf("Configuration template with id '%s' not found", id);
         throw new IllegalArgumentException("Configuration template with id '" + id + "' not found");
      }

      // If the name is changing, check that the new name is not already taken.
      if (!existing.name.equals(incoming.name)) {
         if (templateRepository.findByName(incoming.name).isPresent()) {
            logger.errorf("A configuration template named '%s' already exists", incoming.name);
            throw new IllegalArgumentException("A configuration template named '" + incoming.name + "' already exists");
         }
         existing.name = incoming.name;
      }

      existing.description = incoming.description;
      existing.oauth2Configuration = incoming.oauth2Configuration;

      templateRepository.persistAndFlush(existing);
      return existing;
   }

   /**
    * Deletes a configuration template by ID.
    * @param id the ID of the template to delete
    * @throws IllegalArgumentException if the template is not found
    */
   @Transactional
   public void deleteConfigurationTemplate(String id) {
      logger.debugf("Deleting configuration template with id '%s'", id);
      ConfigurationTemplate template = templateRepository.findById(id);
      if (template == null) {
         logger.errorf("Configuration template with id '%s' not found", id);
         throw new IllegalArgumentException("Configuration template with id '" + id + "' not found");
      }
      templateRepository.delete(template);
   }
}
