package com.vodplatform.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import com.vodplatform.auth.config.AuthTokenProperties;
import com.vodplatform.auth.persistence.RoleEntity;
import com.vodplatform.auth.persistence.UserEntity;
import com.vodplatform.auth.persistence.UserStatus;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class JwtAccessTokenServiceTest {

    private static final String SECRET = "test-only-secret-with-at-least-32-bytes";

    @Test
    void issuesSignedJwtWithFrozenUserClaimsAndConfiguredExpiration() {
        Instant issuedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Clock clock = Clock.fixed(issuedAt, ZoneOffset.UTC);
        AuthTokenProperties properties = new AuthTokenProperties(
                SECRET,
                Duration.ofMinutes(15),
                Duration.ofDays(7)
        );
        SecretKey secretKey = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(secretKey));
        JwtAccessTokenService service = new JwtAccessTokenService(encoder, properties, clock);
        RoleEntity role = mock(RoleEntity.class);
        when(role.getName()).thenReturn("ROLE_USER");
        UUID userId = UUID.randomUUID();
        UserEntity user = new UserEntity(
                userId,
                "viewer@example.com",
                "bcrypt-hash",
                "Viewer",
                UserStatus.ACTIVE,
                issuedAt,
                issuedAt
        );
        user.addRole(role);

        IssuedAccessToken issuedToken = service.issue(user);

        Jwt jwt = NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build()
                .decode(issuedToken.value());
        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString("userId")).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString("email")).isEqualTo("viewer@example.com");
        assertThat(jwt.getClaimAsStringList("roles")).isEqualTo(List.of("ROLE_USER"));
        assertThat(jwt.getIssuedAt()).isEqualTo(issuedAt);
        assertThat(jwt.getExpiresAt()).isEqualTo(issuedAt.plus(Duration.ofMinutes(15)));
        assertThat(issuedToken.expiresInSeconds()).isEqualTo(900);
    }
}
