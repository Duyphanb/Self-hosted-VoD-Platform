package com.vodplatform.progress.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** D12: invoked only after an accepted progress upsert, within the same transaction. */
@Repository
public class WatchHistoryWriter {
    private final JdbcTemplate jdbc;
    public WatchHistoryWriter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void record(UUID user, UUID movie, boolean finished, Instant now) {
        Timestamp time = Timestamp.from(now);
        jdbc.update("""
                INSERT INTO watch_history(id,user_id,movie_id,started_at,last_watched_at,completed_at)
                VALUES (?,?,?,?,?,?)
                ON CONFLICT (user_id,movie_id) DO UPDATE
                SET last_watched_at=EXCLUDED.last_watched_at,
                    completed_at=COALESCE(watch_history.completed_at,EXCLUDED.completed_at)
                """, UUID.randomUUID(), user, movie, time, time, finished ? time : null);
    }
}
