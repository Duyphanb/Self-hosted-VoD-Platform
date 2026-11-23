package com.vodplatform.auth.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    @Query("""
            select t.user.id as userId, t.sessionId as sessionId from RefreshTokenEntity t
            where t.tokenHash = :tokenHash and t.sessionId is not null
            """)
    Optional<TokenLocation> locate(String tokenHash);

    interface TokenLocation {
        UUID getUserId();

        UUID getSessionId();
    }
}
