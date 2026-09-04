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
package io.reshapr.proxy.security;

import io.reshapr.proxy.audit.AuditLogger;
import io.reshapr.proxy.audit.AuthenticationFailureAuditEvent;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.ExpositionEntry;
import io.reshapr.proxy.registry.GatewayRegistry;
import io.reshapr.proxy.registry.OAuth2ConfigurationEntry;
import io.reshapr.proxy.registry.ServiceEntry;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SecureEndpointFilter}, focusing on conformance with:
 * <ul>
 *   <li>RFC 6750 (Bearer Token Usage): status codes and {@code WWW-Authenticate} challenges,</li>
 *   <li>RFC 9728 (Protected Resource Metadata): {@code resource_metadata} pointer required by the MCP
 *       Authorization specification on 401 responses,</li>
 *   <li>RFC 9068 (JWT Access Token profile): {@code typ} header values ({@code JWT} vs {@code at+jwt}),</li>
 *   <li>RFC 8707 (Resource Indicators): audience binding of access tokens (MCP MUST).</li>
 * </ul>
 * Tests marked {@code @Disabled} document desired behaviors that are not implemented yet; enable them
 * along with the corresponding filter change.
 * @author laurent
 */
class SecureEndpointFilterTest {

   private static final String ISSUER = "https://auth.example.com/realms/test";
   private static final String OTHER_ISSUER = "https://rogue.example.com";
   private static final String FQDN = "localhost:7777";
   private static final String EXPOSITION_PATH = "/mcp/exp1";
   private static final String CANONICAL_RESOURCE = "http://" + FQDN + EXPOSITION_PATH;
   private static final String RESOURCE_METADATA_URL =
         "http://" + FQDN + "/.well-known/oauth-protected-resource" + EXPOSITION_PATH;

   private static RSAKey rsaKey;
   private static RSAKey rogueKey;
   private static HttpServer jwksServer;
   private static String jwksUri;
   private static String altJwksUri;
   private static final AtomicInteger jwksHits = new AtomicInteger();
   private static final AtomicInteger altJwksHits = new AtomicInteger();

   private GatewayRegistry gatewayRegistry;
   private AuditLogger auditLogger;
   private SecureEndpointFilter filter;

   @BeforeAll
   static void startJwksServer() throws Exception {
      rsaKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
      rogueKey = new RSAKeyGenerator(2048).keyID("test-key").generate();

      String jwksJson = new JWKSet(rsaKey.toPublicJWK()).toString();
      jwksServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      jwksServer.createContext("/jwks", exchange -> {
         jwksHits.incrementAndGet();
         byte[] body = jwksJson.getBytes(StandardCharsets.UTF_8);
         exchange.getResponseHeaders().add("Content-Type", "application/json");
         exchange.sendResponseHeaders(200, body.length);
         try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
         }
      });
      jwksServer.createContext("/alt-jwks", exchange -> {
         altJwksHits.incrementAndGet();
         byte[] body = jwksJson.getBytes(StandardCharsets.UTF_8);
         exchange.getResponseHeaders().add("Content-Type", "application/json");
         exchange.sendResponseHeaders(200, body.length);
         try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
         }
      });
      jwksServer.start();
      int port = jwksServer.getAddress().getPort();
      jwksUri = "http://localhost:" + port + "/jwks";
      altJwksUri = "http://localhost:" + port + "/alt-jwks";
   }

   @AfterAll
   static void stopJwksServer() {
      jwksServer.stop(0);
   }

   @BeforeEach
   void setUp() {
      gatewayRegistry = mock(GatewayRegistry.class);
      auditLogger = mock(AuditLogger.class);
      filter = new SecureEndpointFilter(gatewayRegistry, auditLogger);
      filter.fqdns = List.of(FQDN);
      jwksHits.set(0);
      altJwksHits.set(0);
   }


   // === Fixture helpers =========================================================================

   private void registerOAuth2Exposition(List<String> scopes) {
      registerOAuth2Exposition("exp1", scopes, jwksUri, false);
   }

   private void registerOAuth2Exposition(String expositionId, List<String> scopes, String jwksSetUri, boolean audit) {
      OAuth2ConfigurationEntry oauth2 = new OAuth2ConfigurationEntry(List.of(ISSUER), jwksSetUri, scopes, null, false);
      ConfigurationEntry configuration = new ConfigurationEntry("conf-" + expositionId, "conf", "http://backend",
            30000L, List.of(), List.of(), null, oauth2, null, audit);
      ServiceEntry service = new ServiceEntry("svc1", "org1", "Test Service", "1.0", "REST", List.of());
      ExpositionEntry exposition = new ExpositionEntry(expositionId, "expo", service, configuration, null, List.of());
      when(gatewayRegistry.getExpositionById(expositionId)).thenReturn(exposition);
   }

   private void registerApiKeyExposition(String apiKey, boolean audit) {
      ConfigurationEntry configuration = new ConfigurationEntry("conf-key", "conf", "http://backend",
            30000L, List.of(), List.of(), apiKey, null, null, audit);
      ServiceEntry service = new ServiceEntry("svc1", "org1", "Test Service", "1.0", "REST", List.of());
      ExpositionEntry exposition = new ExpositionEntry("exp1", "expo", service, configuration, null, List.of());
      when(gatewayRegistry.getExpositionById("exp1")).thenReturn(exposition);
   }

   private ContainerRequestContext mcpRequest(String path, String authorizationHeader) {
      ContainerRequestContext ctx = mock(ContainerRequestContext.class);
      UriInfo uriInfo = mock(UriInfo.class);
      when(uriInfo.getPath()).thenReturn(path);
      when(ctx.getUriInfo()).thenReturn(uriInfo);
      when(ctx.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn(authorizationHeader);
      return ctx;
   }

   private ContainerRequestContext bearerRequest(String token) {
      return mcpRequest(EXPOSITION_PATH, "Bearer " + token);
   }

   /** Build and sign a valid access token, letting the customizer tweak the default claims. */
   private String token(JOSEObjectType typ, UnaryOperator<JWTClaimsSet.Builder> customizer) throws Exception {
      Instant now = Instant.now();
      JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
            .subject("user-1")
            .issuer(ISSUER)
            .issueTime(Date.from(now.minusSeconds(10)))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .jwtID(UUID.randomUUID().toString())
            .audience(CANONICAL_RESOURCE);
      builder = customizer.apply(builder);

      SignedJWT jwt = new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).type(typ).build(),
            builder.build());
      jwt.sign(new RSASSASigner(rsaKey));
      return jwt.serialize();
   }

   private String validToken() throws Exception {
      return token(JOSEObjectType.JWT, UnaryOperator.identity());
   }

   private Response abortedResponse(ContainerRequestContext ctx) {
      ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
      verify(ctx).abortWith(captor.capture());
      return captor.getValue();
   }

   private void assertNotAborted(ContainerRequestContext ctx) {
      verify(ctx, never()).abortWith(any());
   }


   // === RFC 6750 / RFC 9728: status codes and WWW-Authenticate challenges ======================

   @Nested
   @DisplayName("RFC 6750 / RFC 9728 status codes and WWW-Authenticate challenges")
   class StatusCodesAndChallenges {

      @Test
      @DisplayName("Missing Authorization header -> 401 with resource_metadata challenge (MCP + RFC 9728)")
      void missingBearerReturns401WithResourceMetadata() throws Exception {
         registerOAuth2Exposition(null);
         ContainerRequestContext ctx = mcpRequest(EXPOSITION_PATH, null);

         filter.filter(ctx);

         Response response = abortedResponse(ctx);
         assertEquals(401, response.getStatus());
         String challenge = response.getHeaderString(HttpHeaders.WWW_AUTHENTICATE);
         assertNotNull(challenge, "401 must carry a WWW-Authenticate challenge (RFC 6750 section 3)");
         assertTrue(challenge.startsWith("Bearer "), "Challenge must use the Bearer scheme");
         assertTrue(challenge.contains("resource_metadata=\"" + RESOURCE_METADATA_URL + "\""),
               "Challenge must point to the protected resource metadata (RFC 9728) but was: " + challenge);
      }

      @Test
      @DisplayName("Non-Bearer Authorization scheme -> 401 with challenge")
      void nonBearerSchemeReturns401() throws Exception {
         registerOAuth2Exposition(null);
         ContainerRequestContext ctx = mcpRequest(EXPOSITION_PATH, "Basic dXNlcjpwYXNz");

         filter.filter(ctx);

         Response response = abortedResponse(ctx);
         assertEquals(401, response.getStatus());
         assertNotNull(response.getHeaderString(HttpHeaders.WWW_AUTHENTICATE));
      }

      @Test
      @DisplayName("Unparseable token -> 401 with error=\"invalid_token\" (RFC 6750, not 400)")
      void malformedTokenReturns401WithInvalidTokenError() throws Exception {
         registerOAuth2Exposition(null);
         ContainerRequestContext ctx = bearerRequest("this-is-not-a-jwt");

         filter.filter(ctx);

         Response response = abortedResponse(ctx);
         assertEquals(401, response.getStatus(), "Malformed tokens are invalid_token -> 401, not 400");
         String challenge = response.getHeaderString(HttpHeaders.WWW_AUTHENTICATE);
         assertNotNull(challenge);
         assertTrue(challenge.contains("error=\"invalid_token\""), "Challenge was: " + challenge);
         assertTrue(challenge.contains("resource_metadata=\""), "Challenge was: " + challenge);
      }

      @Test
      @DisplayName("Expired token -> 401 with error=\"invalid_token\"")
      void expiredTokenReturns401WithInvalidTokenError() throws Exception {
         registerOAuth2Exposition(null);
         Instant past = Instant.now().minusSeconds(600);
         String expired = token(JOSEObjectType.JWT, b -> b
               .issueTime(Date.from(past))
               .expirationTime(Date.from(past.plusSeconds(60))));
         ContainerRequestContext ctx = bearerRequest(expired);

         filter.filter(ctx);

         Response response = abortedResponse(ctx);
         assertEquals(401, response.getStatus());
         String challenge = response.getHeaderString(HttpHeaders.WWW_AUTHENTICATE);
         assertNotNull(challenge);
         assertTrue(challenge.contains("error=\"invalid_token\""), "Challenge was: " + challenge);
      }

      @Test
      @DisplayName("Token from an unknown issuer -> 401 (MCP: only configured authorization servers)")
      void unknownIssuerReturns401() throws Exception {
         registerOAuth2Exposition(null);
         String foreign = token(JOSEObjectType.JWT, b -> b.issuer(OTHER_ISSUER));
         ContainerRequestContext ctx = bearerRequest(foreign);

         filter.filter(ctx);

         Response response = abortedResponse(ctx);
         assertEquals(401, response.getStatus());
         assertTrue(response.getHeaderString(HttpHeaders.WWW_AUTHENTICATE).contains("error=\"invalid_token\""));
      }

      @Test
      @DisplayName("Token signed with an unknown key -> 401")
      void badSignatureReturns401() throws Exception {
         registerOAuth2Exposition(null);
         // Same kid as the published key but signed with a different private key.
         SignedJWT jwt = new SignedJWT(
               new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).type(JOSEObjectType.JWT).build(),
               new JWTClaimsSet.Builder()
                     .subject("user-1").issuer(ISSUER)
                     .issueTime(new Date())
                     .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                     .jwtID(UUID.randomUUID().toString())
                     .build());
         jwt.sign(new RSASSASigner(rogueKey));
         ContainerRequestContext ctx = bearerRequest(jwt.serialize());

         filter.filter(ctx);

         assertEquals(401, abortedResponse(ctx).getStatus());
      }

      @Test
      @DisplayName("Symmetrically-signed token (HS256) -> 401 (asymmetric-only allowlist, RFC 8725)")
      void hs256TokenReturns401() throws Exception {
         registerOAuth2Exposition(null);
         SignedJWT jwt = new SignedJWT(
               new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(),
               new JWTClaimsSet.Builder()
                     .subject("user-1").issuer(ISSUER)
                     .issueTime(new Date())
                     .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                     .jwtID(UUID.randomUUID().toString())
                     .build());
         jwt.sign(new MACSigner("0123456789abcdef0123456789abcdef"));
         ContainerRequestContext ctx = bearerRequest(jwt.serialize());

         filter.filter(ctx);

         assertEquals(401, abortedResponse(ctx).getStatus());
      }

      @Test
      @DisplayName("Invalid JWKS URI in configuration -> 401")
      void invalidJwksUriReturns401() throws Exception {
         registerOAuth2Exposition("exp1", null, "not a valid uri", false);
         ContainerRequestContext ctx = bearerRequest(validToken());

         filter.filter(ctx);

         assertEquals(401, abortedResponse(ctx).getStatus());
      }
   }


   // === RFC 9068: JWT typ header support ========================================================

   @Nested
   @DisplayName("RFC 9068 token typ support")
   class TokenTypes {

      @Test
      @DisplayName("typ: JWT (Keycloak style) is accepted")
      void plainJwtTypIsAccepted() throws Exception {
         registerOAuth2Exposition(null);
         ContainerRequestContext ctx = bearerRequest(token(JOSEObjectType.JWT, UnaryOperator.identity()));

         filter.filter(ctx);

         assertNotAborted(ctx);
         verify(ctx).setProperty(SecureEndpointFilter.USER_ID_PROPERTY, "user-1");
         verify(ctx).setProperty(SecureEndpointFilter.ISSUER_PROPERTY, ISSUER);
      }

      @Test
      @DisplayName("Absent typ header is accepted (RFC 7519: typ is optional)")
      void absentTypIsAccepted() throws Exception {
         registerOAuth2Exposition(null);
         ContainerRequestContext ctx = bearerRequest(token(null, UnaryOperator.identity()));

         filter.filter(ctx);

         assertNotAborted(ctx);
      }

      @Test
      @DisplayName("typ: at+jwt (RFC 9068 / Auth0, Okta, Spring AS) is accepted")
      void atPlusJwtTypIsAccepted() throws Exception {
         registerOAuth2Exposition(null);
         ContainerRequestContext ctx = bearerRequest(token(new JOSEObjectType("at+jwt"), UnaryOperator.identity()));

         filter.filter(ctx);

         assertNotAborted(ctx);
         verify(ctx).setProperty(SecureEndpointFilter.USER_ID_PROPERTY, "user-1");
      }

      @Test
      //@Disabled("Pending fix: make the jti claim optional (no replay cache uses it; several AS do not emit it)")
      @DisplayName("Token without jti claim is accepted")
      void tokenWithoutJtiIsAccepted() throws Exception {
         registerOAuth2Exposition(null);
         ContainerRequestContext ctx = bearerRequest(token(JOSEObjectType.JWT, b -> b.jwtID(null)));

         filter.filter(ctx);

         assertNotAborted(ctx);
      }
   }


   // === JWKSource caching =======================================================================

   @Nested
   @DisplayName("JWKSource caching")
   class JwksCaching {

      @Test
      @DisplayName("JWKS endpoint is fetched only once across successive requests")
      void jwksFetchedOnceAcrossRequests() throws Exception {
         registerOAuth2Exposition(null);

         for (int i = 0; i < 3; i++) {
            ContainerRequestContext ctx = bearerRequest(validToken());
            filter.filter(ctx);
            assertNotAborted(ctx);
         }

         assertEquals(1, jwksHits.get(),
               "JWKSource must be cached per JWKS URI: expected a single JWKS fetch across requests");
      }

      @Test
      @DisplayName("Distinct JWKS URIs use distinct cached sources")
      void distinctJwksUrisUseDistinctSources() throws Exception {
         registerOAuth2Exposition("exp1", null, jwksUri, false);
         registerOAuth2Exposition("exp2", null, altJwksUri, false);

         ContainerRequestContext ctx1 = bearerRequest(validToken());
         filter.filter(ctx1);
         assertNotAborted(ctx1);

         ContainerRequestContext ctx2 = mcpRequest("/mcp/exp2", "Bearer " + token(JOSEObjectType.JWT, b -> b.audience("http://localhost:7777/mcp/exp2")));
         filter.filter(ctx2);
         assertNotAborted(ctx2);

         assertEquals(1, jwksHits.get());
         assertEquals(1, altJwksHits.get());
      }
   }


   // === Scope validation ========================================================================

   @Nested
   @DisplayName("Scope validation")
   class Scopes {

      @Test
      @DisplayName("Space-separated scope string containing all required scopes is accepted")
      void scopeStringIsAccepted() throws Exception {
         registerOAuth2Exposition(List.of("read", "write"));
         ContainerRequestContext ctx = bearerRequest(
               token(JOSEObjectType.JWT, b -> b.claim("scope", "read write extra")));

         filter.filter(ctx);

         assertNotAborted(ctx);
      }

      @Test
      @DisplayName("scp claim (string form) is accepted as an alternative to scope")
      void scpStringIsAccepted() throws Exception {
         registerOAuth2Exposition(List.of("read"));
         ContainerRequestContext ctx = bearerRequest(
               token(JOSEObjectType.JWT, b -> b.claim("scp", "read write")));

         filter.filter(ctx);

         assertNotAborted(ctx);
      }

      @Test
      @Disabled("Pending fix: getStringClaim throws ParseException on list claims, so the List<String> "
            + "fallback branch is unreachable — list-valued scope/scp claims are rejected with 401 today")
      @DisplayName("List-valued scp claim (Entra ID style) is accepted")
      void scpListIsAccepted() throws Exception {
         registerOAuth2Exposition(List.of("read"));
         ContainerRequestContext ctx = bearerRequest(
               token(JOSEObjectType.JWT, b -> b.claim("scp", List.of("read", "write"))));

         filter.filter(ctx);

         assertNotAborted(ctx);
      }

      @Test
      @DisplayName("Required scopes but no scope claim -> 403")
      void missingScopeClaimReturns403() throws Exception {
         registerOAuth2Exposition(List.of("read"));
         ContainerRequestContext ctx = bearerRequest(validToken());

         filter.filter(ctx);

         assertEquals(403, abortedResponse(ctx).getStatus());
      }

      @Test
      @DisplayName("Scope claim missing one required scope -> 403")
      void partialScopesReturns403() throws Exception {
         registerOAuth2Exposition(List.of("read", "write"));
         ContainerRequestContext ctx = bearerRequest(
               token(JOSEObjectType.JWT, b -> b.claim("scope", "read")));

         filter.filter(ctx);

         assertEquals(403, abortedResponse(ctx).getStatus());
      }

      @Test
      @Disabled("Improvement: RFC 6750 section 3.1 recommends error=\"insufficient_scope\" (+ scope attribute) on 403")
      @DisplayName("403 for missing scopes carries error=\"insufficient_scope\"")
      void insufficientScopeChallengeOn403() throws Exception {
         registerOAuth2Exposition(List.of("read"));
         ContainerRequestContext ctx = bearerRequest(validToken());

         filter.filter(ctx);

         Response response = abortedResponse(ctx);
         assertEquals(403, response.getStatus());
         String challenge = response.getHeaderString(HttpHeaders.WWW_AUTHENTICATE);
         assertNotNull(challenge);
         assertTrue(challenge.contains("error=\"insufficient_scope\""));
      }
   }


   // === Audience / resource binding (MCP MUST, RFC 8707) =======================================

   @Nested
   @DisplayName("Audience and resource binding (MCP MUST)")
   class AudienceBinding {

      @Test
      @DisplayName("resource claim matching the canonical exposition URI is accepted")
      void matchingResourceClaimIsAccepted() throws Exception {
         registerOAuth2Exposition(null);
         ContainerRequestContext ctx = bearerRequest(
               token(JOSEObjectType.JWT, b -> b.claim("resource", CANONICAL_RESOURCE)));

         filter.filter(ctx);

         assertNotAborted(ctx);
      }

      @Test
      @DisplayName("resource claim bound to another resource -> 403 (confused deputy prevention)")
      void mismatchedResourceClaimReturns403() throws Exception {
         registerOAuth2Exposition(null);
         ContainerRequestContext ctx = bearerRequest(
               token(JOSEObjectType.JWT, b -> b.claim("resource", "http://other.example.com/mcp/other")));

         filter.filter(ctx);

         assertEquals(403, abortedResponse(ctx).getStatus());
      }

      @Test
      @DisplayName("aud claim bound to another resource -> rejected")
      void mismatchedAudienceIsRejected() throws Exception {
         registerOAuth2Exposition(null);
         ContainerRequestContext ctx = bearerRequest(
               token(JOSEObjectType.JWT, b -> b.audience("https://another-api.example.com")));

         filter.filter(ctx);

         Response response = abortedResponse(ctx);
         assertTrue(response.getStatus() == 401 || response.getStatus() == 403,
               "Token with a foreign audience must be rejected but was accepted");
      }

      @Test
      @DisplayName("aud claim matching the canonical exposition URI is accepted")
      void matchingAudienceIsAccepted() throws Exception {
         registerOAuth2Exposition(null);
         ContainerRequestContext ctx = bearerRequest(
               token(JOSEObjectType.JWT, b -> b.audience(CANONICAL_RESOURCE)));

         filter.filter(ctx);

         assertNotAborted(ctx);
      }

      @Test
      @DisplayName("serviceId claim not matching the exposed service -> 403")
      void mismatchedServiceIdReturns403() throws Exception {
         registerOAuth2Exposition(null);
         ContainerRequestContext ctx = bearerRequest(
               token(JOSEObjectType.JWT, b -> b.claim("serviceId", "some-other-service")));

         filter.filter(ctx);

         assertEquals(403, abortedResponse(ctx).getStatus());
      }
   }


   // === API key checks ==========================================================================

   @Nested
   @DisplayName("API key checks")
   class ApiKeys {

      @Test
      @DisplayName("Valid API key is accepted")
      void validApiKeyIsAccepted() throws Exception {
         registerApiKeyExposition("secret-key", false);
         ContainerRequestContext ctx = mcpRequest(EXPOSITION_PATH, null);
         when(ctx.getHeaderString("x-reshapr-key")).thenReturn("secret-key");

         filter.filter(ctx);

         assertNotAborted(ctx);
      }

      @Test
      @DisplayName("Invalid API key -> 401")
      void invalidApiKeyReturns401() throws Exception {
         registerApiKeyExposition("secret-key", false);
         ContainerRequestContext ctx = mcpRequest(EXPOSITION_PATH, null);
         when(ctx.getHeaderString("x-reshapr-key")).thenReturn("wrong-key");

         filter.filter(ctx);

         assertEquals(401, abortedResponse(ctx).getStatus());
      }

      @Test
      @DisplayName("Missing API key -> 401")
      void missingApiKeyReturns401() throws Exception {
         registerApiKeyExposition("secret-key", false);
         ContainerRequestContext ctx = mcpRequest(EXPOSITION_PATH, null);

         filter.filter(ctx);

         assertEquals(401, abortedResponse(ctx).getStatus());
      }
   }


   // === Audit and bypass behaviors ==============================================================

   @Nested
   @DisplayName("Audit and bypass behaviors")
   class AuditAndBypass {

      @Test
      @DisplayName("Authentication failure emits an audit event when audit is enabled")
      void authFailureEmitsAuditEventWhenEnabled() throws Exception {
         registerOAuth2Exposition("exp1", null, jwksUri, true);
         ContainerRequestContext ctx = mcpRequest(EXPOSITION_PATH, null);

         filter.filter(ctx);

         // Audit emission is asynchronous (virtual thread).
         ArgumentCaptor<AuthenticationFailureAuditEvent> captor =
               ArgumentCaptor.forClass(AuthenticationFailureAuditEvent.class);
         verify(auditLogger, timeout(2000)).logAuthFailure(captor.capture());
         assertEquals(AuthenticationFailureAuditEvent.REASON_MISSING_BEARER, captor.getValue().reason());
      }

      @Test
      @DisplayName("No audit event when audit is disabled on the configuration")
      void noAuditEventWhenDisabled() throws Exception {
         registerOAuth2Exposition("exp1", null, jwksUri, false);
         ContainerRequestContext ctx = mcpRequest(EXPOSITION_PATH, null);

         filter.filter(ctx);

         abortedResponse(ctx); // Still aborted with 401...
         verifyNoInteractions(auditLogger); // ... but silently.
      }

      @Test
      @DisplayName("Non-MCP paths bypass security checks")
      void nonMcpPathBypassesChecks() throws Exception {
         ContainerRequestContext ctx = mock(ContainerRequestContext.class);
         UriInfo uriInfo = mock(UriInfo.class);
         when(uriInfo.getPath()).thenReturn("/q/health");
         when(ctx.getUriInfo()).thenReturn(uriInfo);

         filter.filter(ctx);

         assertNotAborted(ctx);
         verifyNoInteractions(gatewayRegistry);
      }

      @Test
      @DisplayName("Unknown exposition bypasses checks (documents current behavior)")
      void unknownExpositionBypassesChecks() throws Exception {
         // Note: unresolved expositions are let through and later 404ed by the MCP endpoint.
         ContainerRequestContext ctx = mcpRequest("/mcp/unknown", null);

         filter.filter(ctx);

         assertNotAborted(ctx);
      }

      @Test
      @DisplayName("Exposition without apiKey nor OAuth2 configuration bypasses checks")
      void unsecuredConfigurationBypassesChecks() throws Exception {
         ConfigurationEntry configuration = new ConfigurationEntry("conf1", "conf", "http://backend",
               30000L, List.of(), List.of(), null, null, null, false);
         ServiceEntry service = new ServiceEntry("svc1", "org1", "Test Service", "1.0", "REST", List.of());
         when(gatewayRegistry.getExpositionById("exp1"))
               .thenReturn(new ExpositionEntry("exp1", "expo", service, configuration, null, List.of()));
         ContainerRequestContext ctx = mcpRequest(EXPOSITION_PATH, null);

         filter.filter(ctx);

         assertNotAborted(ctx);
      }
   }
}
