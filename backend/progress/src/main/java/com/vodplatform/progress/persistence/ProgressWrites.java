package com.vodplatform.progress.persistence;

import com.vodplatform.progress.service.ProgressWriteValues;
import com.vodplatform.progress.service.StoredProgress;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** ADR-011 D9 guarded upsert. RETURNING is empty precisely when an older write loses. */
@Repository
public class ProgressWrites {
    private final JdbcTemplate jdbc;

    public ProgressWrites(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<StoredProgress> upsert(UUID user, UUID movie, ProgressWriteValues value, Instant now) {
        return jdbc.query("""
                INSERT INTO playback_progress
                  (user_id,movie_id,current_seconds,duration_seconds,finished,last_played_at,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?)
                ON CONFLICT (user_id,movie_id) DO UPDATE
                SET current_seconds=EXCLUDED.current_seconds, duration_seconds=EXCLUDED.duration_seconds,
                    finished=EXCLUDED.finished, last_played_at=EXCLUDED.last_played_at, updated_at=EXCLUDED.updated_at
                WHERE playback_progress.last_played_at <= EXCLUDED.last_played_at
                RETURNING current_seconds,duration_seconds,finished,last_played_at
                """, ProgressWrites::row, user, movie, value.currentSeconds(), value.durationSeconds(), value.finished(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)).stream().findFirst();
    }

    /** Fresh JDBC read avoids stale JPA state after a native upsert or a rejected concurrent save. */
    public StoredProgress stored(UUID user, UUID movie) {
        return jdbc.query("select current_seconds,duration_seconds,finished,last_played_at "
                + "from playback_progress where user_id=? and movie_id=?", ProgressWrites::row, user, movie)
                .getFirst();
    }

    private static StoredProgress row(ResultSet rs, int index) throws SQLException {
        return new StoredProgress(rs.getInt("current_seconds"), rs.getObject("duration_seconds", Integer.class),
                rs.getBoolean("finished"), rs.getTimestamp("last_played_at").toInstant());
    }
}
