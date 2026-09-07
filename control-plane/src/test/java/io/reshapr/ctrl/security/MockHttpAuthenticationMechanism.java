package io.reshapr.ctrl.security;

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpCredentialTransport;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.Set;

/**
 * Mock HTTP authentication mechanism for tests.
 * Always authenticates the request to avoid 401 Unauthorized errors in REST-assured tests.
 * @author vaishnav
 */
@Alternative
@Priority(2) // Higher priority than CustomHttpAuthenticationMechanism which is 1
@ApplicationScoped
public class MockHttpAuthenticationMechanism implements HttpAuthenticationMechanism {

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        // Always return a valid test user identity
        return Uni.createFrom().item(QuarkusSecurityIdentity.builder()
                .setPrincipal(() -> "test-user")
                .addRole("user")
                .build());
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        return Uni.createFrom().nullItem();
    }

    @Override
    public Set<Class<? extends io.quarkus.security.identity.request.AuthenticationRequest>> getCredentialTypes() {
        return Set.of();
    }

    @Override
    public Uni<HttpCredentialTransport> getCredentialTransport(RoutingContext context) {
        return Uni.createFrom().nullItem();
    }
}
