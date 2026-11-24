package com.vodplatform.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.vodplatform.auth.persistence.AuthSessionEntity;
import com.vodplatform.auth.persistence.RefreshTokenEntity;
import com.vodplatform.auth.persistence.RefreshTokenRepository;
import com.vodplatform.auth.persistence.UserEntity;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
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
        RefreshTokenService service = new RefreshTokenService(
                repository,
                secureRandom,
                Clock.fixed(createdAt, ZoneOffset.UTC)
        );
        UserEntity user = mock(UserEntity.class);

        Instant expiresAt = createdAt.plus(Duration.ofDays(6));
        var session = new AuthSessionEntity(UUID.randomUUID(), UUID.randomUUID(), createdAt.minusSeconds(1), expiresAt);
        IssuedRefreshToken issuedToken = service.issue(user, session);

        ArgumentCaptor<RefreshTokenEntity> entityCaptor = ArgumentCaptor.forClass(RefreshTokenEntity.class);
        verify(repository).save(entityCaptor.capture());
        RefreshTokenEntity storedToken = entityCaptor.getValue();
        assertThat(issuedToken.value()).hasSize(43);
        assertThat(issuedToken.hash()).isEqualTo(service.hash(issuedToken.value()));
        assertThat(storedToken.getTokenHash()).isEqualTo(issuedToken.hash());
        assertThat(storedToken.getTokenHash()).isNotEqualTo(issuedToken.value());
        assertThat(storedToken.getUser()).isSameAs(user);
        assertThat(storedToken.getCreatedAt()).isEqualTo(createdAt);
        assertThat(storedToken.getSessionId()).isEqualTo(session.getId());
        assertThat(storedToken.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(storedToken.getRevokedAt()).isNull();
    }
}
