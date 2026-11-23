package com.vodplatform.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vodplatform.auth.persistence.SessionState;
import com.vodplatform.auth.persistence.UserStatus;
import com.vodplatform.auth.service.SessionStateCache;
import com.vodplatform.auth.service.SessionStateReader;
import java.time.Clock;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

class AccessTokenAuthenticationConverterTest {

    private static final UUID USER_ID = UUID.fromString("3d21e78c-548e-47e3-9267-32ebf8bf25aa");

    private static final UUID SESSION_ID = UUID.randomUUID();
    private final SessionStateCache cache = mock(SessionStateCache.class);
    private final AccessTokenAuthenticationConverter converter = new AccessTokenAuthenticationConverter(
            cache, mock(SessionStateReader.class), Clock.systemUTC());

    @Test
    void exactFrozenRolesBecomeAuthoritiesAndImmutablePrincipalData() {
        when(cache.read(SESSION_ID)).thenReturn(Optional.of(
                new SessionState(USER_ID, UserStatus.ACTIVE, 4, Instant.now().plusSeconds(300), null, null)));
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
                .claim("sid", SESSION_ID.toString())
                .claim("ver", 4)
                .build();
    }
}
