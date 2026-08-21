package com.vodplatform.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

class AccessTokenAuthenticationConverterTest {

    private static final UUID USER_ID = UUID.fromString("3d21e78c-548e-47e3-9267-32ebf8bf25aa");

    private final AccessTokenAuthenticationConverter converter = new AccessTokenAuthenticationConverter();

    @Test
    void exactFrozenRolesBecomeAuthoritiesAndImmutablePrincipalData() {
        UsernamePasswordAuthenticationToken authentication =
                (UsernamePasswordAuthenticationToken) converter.convert(jwt(
                        USER_ID.toString(),
                        USER_ID.toString(),
                        "viewer@example.com",
                        List.of("ROLE_USER", "ROLE_ADMIN")
                ));

        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getCredentials()).isNull();
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN", "ROLE_USER");
        assertThat(authentication.getPrincipal())
                .isEqualTo(new AuthenticatedUserPrincipal(
                        USER_ID,
                        "viewer@example.com",
                        List.of("ROLE_ADMIN", "ROLE_USER")
                ));
    }

    @Test
    void subjectMustMatchUserIdClaim() {
        assertThatThrownBy(() -> converter.convert(jwt(
                UUID.randomUUID().toString(),
                USER_ID.toString(),
                "viewer@example.com",
                List.of("ROLE_USER")
        ))).isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void unknownRoleIsRejectedInsteadOfBecomingAnAuthority() {
        assertThatThrownBy(() -> converter.convert(jwt(
                USER_ID.toString(),
                USER_ID.toString(),
                "viewer@example.com",
                List.of("ROLE_SUPERUSER")
        ))).isInstanceOf(InvalidBearerTokenException.class);
    }

    private Jwt jwt(String subject, String userId, String email, List<String> roles) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("signed-token-value")
                .header("alg", "HS256")
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("userId", userId)
                .claim("email", email)
                .claim("roles", roles)
                .build();
    }
}
