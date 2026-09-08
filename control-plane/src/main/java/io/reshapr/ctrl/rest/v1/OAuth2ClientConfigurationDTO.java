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
import io.reshapr.json.HtmlEncodedStringDeserializer;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * Data Transfer Object (DTO) for third-party OAuth2 configuration in the Reshapr control plane.
 * @param clientId The OAuth2 client ID
 * @param clientSecret The OAuth2 client secret if any
 * @param authorizationEndpoint The OAuth2 authorization endpoint URL
 * @param tokenEndpoint The OAuth2 token endpoint URL
 * @param scopes The OAuth2 scopes to request (optional, mainly for Client Credentials)
 * @author laurent
 */
@RegisterForReflection
public record OAuth2ClientConfigurationDTO(
      @JsonDeserialize(using = HtmlEncodedStringDeserializer.class)
      String clientId,
      @JsonDeserialize(using = HtmlEncodedStringDeserializer.class)
      String clientSecret,
      @HttpUrl(message = "Authorization endpoint must be a valid HTTP(S) URL")
      String authorizationEndpoint,
      @HttpUrl(message = "Token endpoint must be a valid HTTP(S) URL")
      String tokenEndpoint,
      List<String> scopes) {
}
