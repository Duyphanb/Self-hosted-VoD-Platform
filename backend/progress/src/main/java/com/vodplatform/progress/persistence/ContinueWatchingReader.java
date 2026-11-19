package com.vodplatform.progress.persistence;

import com.vodplatform.catalog.service.MovieVisibility;
import com.vodplatform.encoding.persistence.VideoAssetStatus;
import com.vodplatform.progress.service.StoredProgress;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.Session;
import org.springframework.stereotype.Repository;

/** Set-based ADR-011 D11 selection; every membership condition precedes the order and limit. */
@Repository
public class ContinueWatchingReader {
    // ADR-010 D14: PostgreSQL (created_at, id) order, restricted to READY before choosing the
    // current asset. ADR-011 D11/D14: 0 < stored position < D is equivalent to the normalizer's
    // 0 < LEAST(position, D) < D. Null/nonpositive D cannot establish resumability.
    private static final String SELECT = """
            SELECT p.movie_id, p.current_seconds, p.duration_seconds, p.finished, p.last_played_at,
                   a.duration_seconds AS current_duration
            FROM playback_progress p
            JOIN movies m ON m.id = p.movie_id
            JOIN LATERAL (
                SELECT v.duration_seconds FROM video_assets v
                WHERE v.movie_id = p.movie_id AND v.status = :ready
                ORDER BY v.created_at DESC, v.id DESC LIMIT 1
            ) a ON true
            WHERE p.user_id = :userId AND m.status IN (:statuses)
              AND NOT p.finished AND a.duration_seconds > 0
              AND p.current_seconds > 0 AND p.current_seconds < a.duration_seconds
            ORDER BY p.last_played_at DESC, p.movie_id DESC
            LIMIT 50
            """;

    private final EntityManager entityManager;

    public ContinueWatchingReader(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Current duration and stored progress are read in the same statement snapshot. */
    public record Selection(UUID movieId, StoredProgress progress, Integer currentDuration) {
    }

    public List<Selection> find(UUID userId, MovieVisibility visibility) {
        return entityManager.unwrap(Session.class).createNativeQuery(SELECT, Object[].class)
                .addScalar("movie_id", UUID.class)
                .addScalar("current_seconds", Integer.class)
                .addScalar("duration_seconds", Integer.class)
                .addScalar("finished", Boolean.class)
                .addScalar("last_played_at", Instant.class)
                .addScalar("current_duration", Integer.class)
                .setParameter("userId", userId)
                .setParameter("ready", VideoAssetStatus.READY.name())
                .setParameterList("statuses", visibility.statuses().stream().map(Enum::name).toList())
                .getResultList().stream()
                .map(row -> new Selection((UUID) row[0],
                        new StoredProgress((Integer) row[1], (Integer) row[2], (Boolean) row[3],
                                (Instant) row[4]), (Integer) row[5]))
                .toList();
    }
}
