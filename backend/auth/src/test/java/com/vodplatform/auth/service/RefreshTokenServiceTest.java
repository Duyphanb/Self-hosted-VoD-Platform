package com.vodplatform.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.vodplatform.auth.config.AuthTokenProperties;
import com.vodplatform.auth.persistence.RefreshTokenEntity;
import com.vodplatform.auth.persistence.RefreshTokenRepository;
import com.vodplatform.auth.persistence.UserEntity;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RefreshTokenServiceTest {

    @Test
    void returnsOpaqueTokenAndPersistsOnlyItsHashWithExpiration() {
        RefreshTokenRepository repository = mock(RefreshTokenRepository.class);
        SecureRandom secureRandom = mock(SecureRandom.class);
        doAnswer(invocation -> {
            byte[] bytes = invocation.getArgument(0);
            Arrays.fill(bytes, (byte) 0x5a);
            return null;
        }).when(secureRandom).nextBytes(any(byte[].class));
        Instant createdAt = Instant.parse("2030-01-01T00:00:00Z");
        AuthTokenProperties properties = new AuthTokenProperties(
                "test-only-secret-with-at-least-32-bytes",
                Duration.ofMinutes(15),
                Duration.ofDays(7)
        );
        RefreshTokenService service = new RefreshTokenService(
                repository,
                properties,
                secureRandom,
                Clock.fixed(createdAt, ZoneOffset.UTC)
        );
        UserEntity user = mock(UserEntity.class);

        IssuedRefreshToken issuedToken = service.issue(user);

        ArgumentCaptor<RefreshTokenEntity> entityCaptor = ArgumentCaptor.forClass(RefreshTokenEntity.class);
        verify(repository).save(entityCaptor.capture());
        RefreshTokenEntity storedToken = entityCaptor.getValue();
        assertThat(issuedToken.value()).hasSize(43);
        assertThat(issuedToken.hash()).isEqualTo(service.hash(issuedToken.value()));
        assertThat(storedToken.getTokenHash()).isEqualTo(issuedToken.hash());
        assertThat(storedToken.getTokenHash()).isNotEqualTo(issuedToken.value());
        assertThat(storedToken.getUser()).isSameAs(user);
        assertThat(storedToken.getCreatedAt()).isEqualTo(createdAt);
        assertThat(storedToken.getExpiresAt()).isEqualTo(createdAt.plus(Duration.ofDays(7)));
        assertThat(storedToken.getRevokedAt()).isNull();
    }
}
