package com.vodplatform.progress.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** V1's (user_id, movie_id) primary key; no cross-module entity associations. */
@Embeddable
public class PlaybackProgressId implements Serializable {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "movie_id", nullable = false)
    private UUID movieId;

    protected PlaybackProgressId() {
    }

    public PlaybackProgressId(UUID userId, UUID movieId) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.movieId = Objects.requireNonNull(movieId, "movieId");
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof PlaybackProgressId that
                && Objects.equals(userId, that.userId) && Objects.equals(movieId, that.movieId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, movieId);
    }
}
