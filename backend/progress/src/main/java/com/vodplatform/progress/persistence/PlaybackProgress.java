package com.vodplatform.progress.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/** Read mapping of the existing V1 table. Flyway owns its schema. */
@Entity
@Table(name = "playback_progress")
public class PlaybackProgress {

    @EmbeddedId
    private PlaybackProgressId id;

    @Column(name = "current_seconds", nullable = false)
    private int currentSeconds;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "finished", nullable = false)
    private boolean finished;

    @Column(name = "last_played_at", nullable = false)
    private Instant lastPlayedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PlaybackProgress() {
    }

    public int getCurrentSeconds() { return currentSeconds; }
    public Integer getDurationSeconds() { return durationSeconds; }
    public boolean isFinished() { return finished; }
    public Instant getLastPlayedAt() { return lastPlayedAt; }
}
