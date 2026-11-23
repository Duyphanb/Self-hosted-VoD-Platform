package com.vodplatform.auth.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AuthSessionRepository extends JpaRepository<AuthSessionEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AuthSessionEntity s where s.id = :id")
    Optional<AuthSessionEntity> lockById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from AuthSessionEntity s
            where s.userId = :userId and s.revokedAt is null and s.expiresAt > :now
            order by s.createdAt, s.id
            """)
    List<AuthSessionEntity> lockActiveByUser(UUID userId, Instant now);

    @Query("""
            select new com.vodplatform.auth.persistence.SessionState(
                u.id, u.status, u.authEpoch, s.expiresAt, s.revokedAt, admin.name
            )
            from AuthSessionEntity s join UserEntity u on u.id = s.userId
            left join u.roles admin on :readAdminRole = true and admin.name = 'ROLE_ADMIN'
            where s.id = :id
            """)
    Optional<SessionState> readState(UUID id, boolean readAdminRole);
}
