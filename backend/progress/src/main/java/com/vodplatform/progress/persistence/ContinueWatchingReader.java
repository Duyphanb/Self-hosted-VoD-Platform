package com.vodplatform.progress.persistence;

import com.vodplatform.catalog.service.MovieVisibility;
import com.vodplatform.progress.service.StoredProgress;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.Session;
import org.springframework.stereotype.Repository;

@Repository
public class ContinueWatchingReader {
    private static final String SELECT = """
            WITH anchors AS (
                SELECT DISTINCT ON (e.title_id) e.title_id, e.id, e.ordinal, p.finished, p.last_played_at
                FROM playback_progress p
                JOIN episodes e ON e.id = p.episode_id
                JOIN titles t ON t.id = e.title_id
                WHERE p.user_id = :userId AND e.status IN (:statuses) AND t.status IN (:statuses)
                ORDER BY e.title_id, p.last_played_at DESC, e.id DESC
            )
            SELECT anchor.title_id, episode.id AS episode_id, episode.ordinal,
                   coalesce(episode.display_label, CAST(episode.ordinal AS varchar(50))) AS display_label, episode.name,
                   anchor.finished AS advance, p.current_seconds, p.duration_seconds, p.finished, p.last_played_at,
                   asset.duration_seconds AS current_duration
            FROM anchors anchor
            LEFT JOIN LATERAL (
                SELECT next.id, next.status FROM episodes next
                WHERE next.title_id = anchor.title_id AND next.ordinal > anchor.ordinal
                ORDER BY next.ordinal LIMIT 1
            ) successor ON anchor.finished
            JOIN episodes episode ON episode.id = CASE WHEN anchor.finished THEN successor.id ELSE anchor.id END
            JOIN current_episode_assets asset ON asset.episode_id = episode.id
            LEFT JOIN playback_progress p ON p.episode_id = episode.id AND p.user_id = :userId
            WHERE (anchor.finished AND successor.status IN (:statuses) AND NOT coalesce(p.finished, false))
               OR (NOT anchor.finished AND p.current_seconds > 0 AND p.current_seconds < asset.duration_seconds)
            ORDER BY anchor.last_played_at DESC, anchor.title_id DESC
            LIMIT 50
            """;

    private final EntityManager entityManager;

    public ContinueWatchingReader(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public record Selection(UUID titleId, UUID episodeId, int ordinal, String displayLabel, String name,
                            boolean advance, StoredProgress progress, Integer currentDuration) {
    }

    public List<Selection> find(UUID userId, MovieVisibility visibility) {
        return entityManager.unwrap(Session.class).createNativeQuery(SELECT, Object[].class)
                .addScalar("title_id", UUID.class)
                .addScalar("episode_id", UUID.class)
                .addScalar("ordinal", Integer.class)
                .addScalar("display_label", String.class)
                .addScalar("name", String.class)
                .addScalar("advance", Boolean.class)
                .addScalar("current_seconds", Integer.class)
                .addScalar("duration_seconds", Integer.class)
                .addScalar("finished", Boolean.class)
                .addScalar("last_played_at", Instant.class)
                .addScalar("current_duration", Integer.class)
                .setParameter("userId", userId)
                .setParameterList("statuses", visibility.statuses().stream().map(Enum::name).toList())
                .getResultList().stream()
                .map(ContinueWatchingReader::selection)
                .toList();
    }

    private static Selection selection(Object[] row) {
        StoredProgress progress = row[6] == null ? null : new StoredProgress((Integer) row[6],
                (Integer) row[7], (Boolean) row[8], (Instant) row[9]);
        return new Selection((UUID) row[0], (UUID) row[1], (Integer) row[2], (String) row[3],
                (String) row[4], (Boolean) row[5], progress, (Integer) row[10]);
    }
}
