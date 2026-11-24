package com.vodplatform.auth.persistence;

import java.time.Instant;
import java.util.UUID;

public record SessionState(
        UUID userId,
        UserStatus status,
        int authEpoch,
        Instant expiresAt,
        Instant revokedAt,
        String adminRole
) {
    public boolean accepts(UUID subject, int epoch, Instant now) {
        return userId.equals(subject) && status == UserStatus.ACTIVE && authEpoch == epoch
                && revokedAt == null && expiresAt.isAfter(now);
    }
}
