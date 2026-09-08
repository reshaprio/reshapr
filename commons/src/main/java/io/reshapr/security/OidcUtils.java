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
package io.reshapr.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Utils for dealing with OpenIO Connect protocol.
 * @author laurent
 */
public class OidcUtils {

   private OidcUtils() {
      // Hide default no argument constructor as it's a utility class.'
   }

   /**
    * Exchange an authorization code for an access token.
    * @param oidcEndpointConfig the OIDC endpoint configuration to use for the exchange.
    * @param objectMapper the ObjectMapper to use for JSON parsing.
    * @param authorizationCode the authorization code to exchange.
    * @param redirectUri the redirect uri used for the exchange.
    * @return The access token.
    * @throws AuthenticationException if the token endpoint returns an error or no access token.
    */
   public static String exchangeAuthorizationCode(OidcEndpointConfig oidcEndpointConfig, ObjectMapper objectMapper,
                                                  String authorizationCode, String redirectUri) throws AuthenticationException {
      // Build the request to the token endpoint.
      HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(oidcEndpointConfig.endpointUrl()))
            .method("POST",
                  HttpRequest.BodyPublishers.ofString(getAuthorizationCodeQueryString(oidcEndpointConfig, authorizationCode, redirectUri)))
            .header("Content-Type", "application/x-www-form-urlencoded");

      try (HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .version(HttpClient.Version.HTTP_1_1).build()) {

         // Send the request to token endpoint.
         HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

         if (response.statusCode() != 200) {
            throw new AuthenticationException("OAuth2 token endpoint returned error " + response.statusCode());
         }

         // Now parse the response to extract the access token.
         JsonNode jsonResponse = objectMapper.readTree(response.body());
         return jsonResponse.get("access_token").asText();
      } catch (Exception e) {
         if (!(e instanceof AuthenticationException)) {
            throw new AuthenticationException("Failed to exchange authorization code: " + e.getMessage());
         }
         throw (AuthenticationException)e;
      }
   }

   /**
    * Request an access token using the OAuth2 {@code client_credentials} grant (machine-to-machine).
    * @param oidcEndpointConfig the token endpoint configuration (endpointUrl, clientId, clientSecret).
    * @param scopes the OAuth2 scopes to request, may be {@code null} or empty.
    * @param objectMapper the ObjectMapper to use for JSON parsing.
    * @return the token response holding the access token and its lifetime in seconds.
    * @throws AuthenticationException if the token endpoint returns an error or no access token.
    */
   public static OidcTokenResponse fetchClientCredentialsToken(OidcEndpointConfig oidcEndpointConfig, List<String> scopes,
                                                               ObjectMapper objectMapper) throws AuthenticationException {
      // Build the request to the token endpoint.
      HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(oidcEndpointConfig.endpointUrl()))
            .method("POST",
                  HttpRequest.BodyPublishers.ofString(getClientCredentialsQueryString(oidcEndpointConfig, scopes)))
            .header("Content-Type", "application/x-www-form-urlencoded");

      try (HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .version(HttpClient.Version.HTTP_1_1).build()) {

         // Send the request to token endpoint.
         HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

         if (response.statusCode() != 200) {
            throw new AuthenticationException("OAuth2 token endpoint returned error " + response.statusCode());
         }

         // Now parse the response to extract the access token and its lifetime.
         JsonNode jsonResponse = objectMapper.readTree(response.body());
         JsonNode accessTokenNode = jsonResponse.get("access_token");
         if (accessTokenNode == null || accessTokenNode.isNull()) {
            throw new AuthenticationException("OAuth2 token endpoint response did not contain an access_token");
         }
         // Use the spec-defined expires_in (RFC 6749 §5.1) as the lifetime signal: it is format-agnostic,
         // unlike a JWT exp claim which is absent from opaque tokens and would couple us to the token format.
         JsonNode expiresInNode = jsonResponse.get("expires_in");
         long expiresIn = expiresInNode != null && expiresInNode.canConvertToLong() ? expiresInNode.asLong() : 0L;
         return new OidcTokenResponse(accessTokenNode.asText(), expiresIn);
      } catch (Exception e) {
         if (!(e instanceof AuthenticationException)) {
            throw new AuthenticationException("Failed to fetch client credentials token: " + e.getMessage());
         }
         throw (AuthenticationException) e;
      }
   }

   private static String getAuthorizationCodeQueryString(OidcEndpointConfig oidcEndpointConfig, String authorizationCode, String redirectUri) {
      StringBuilder queryString = new StringBuilder("grant_type=authorization_code");
      queryString.append("&code=").append(authorizationCode);
      queryString.append("&redirect_uri=").append(URLEncoder.encode(redirectUri, StandardCharsets.UTF_8));
      queryString.append("&client_id=").append(oidcEndpointConfig.clientId());
      if (oidcEndpointConfig.clientSecret() != null) {
         queryString.append("&client_secret=").append(oidcEndpointConfig.clientSecret());
      }
      return queryString.toString();
   }

   private static String getClientCredentialsQueryString(OidcEndpointConfig oidcEndpointConfig, List<String> scopes) {
      StringBuilder queryString = new StringBuilder("grant_type=client_credentials");
      queryString.append("&client_id=").append(URLEncoder.encode(oidcEndpointConfig.clientId(), StandardCharsets.UTF_8));
      if (oidcEndpointConfig.clientSecret() != null) {
         queryString.append("&client_secret=").append(URLEncoder.encode(oidcEndpointConfig.clientSecret(), StandardCharsets.UTF_8));
      }
      if (scopes != null && !scopes.isEmpty()) {
         queryString.append("&scope=").append(URLEncoder.encode(String.join(" ", scopes), StandardCharsets.UTF_8));
      }
      return queryString.toString();
   }

   /**
    * Configuration of an OIDC endpoint.
    * @param endpointUrl the endpoint URL.
    * @param clientId the client ID.
    * @param clientSecret the client secret.
    */
   @RegisterForReflection
   public record OidcEndpointConfig(String endpointUrl, String clientId, String clientSecret) {
   }

   /**
    * Response of an OAuth2 token request.
    * @param accessToken the issued access token.
    * @param expiresInSeconds the token lifetime in seconds ({@code 0} when the server did not provide one).
    */
   @RegisterForReflection
   public record OidcTokenResponse(String accessToken, long expiresInSeconds) {
   }
}
