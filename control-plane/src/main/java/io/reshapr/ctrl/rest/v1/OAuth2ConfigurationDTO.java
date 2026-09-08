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
package io.reshapr.ctrl.rest.v1;

import io.reshapr.validation.HttpUrl;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * Data Transfer Object (DTO) for OAuth2 configuration in the Reshapr control plane.
 * @param authorizationServers List of authorization server URLs
 * @param jwksUri URI for the JSON Web Key Set (JWKS)
 * @param scopes List of OAuth2 scopes
 * @author laurent
 */
@RegisterForReflection
public record OAuth2ConfigurationDTO(
      List<@HttpUrl(message = "Authorization server must be a valid HTTP(S) URL") String> authorizationServers,
      @HttpUrl(message = "JWKS URI endpoint must be a valid HTTP(S) URL")
      String jwksUri,
      List<String> scopes,
      List<String> staticAudiences,
      boolean disableAudienceValidation
) {
}
