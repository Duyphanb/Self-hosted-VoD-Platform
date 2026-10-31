package com.vodplatform.catalog.service;

import com.vodplatform.catalog.persistence.MovieStatus;
import java.util.Objects;
import java.util.UUID;

/**
 * The movie identity and status read under the caller's transaction lock. Only
 * {@link MovieRowLocks} creates this token; use it inside that same transaction.
 */
public final class LockedMovie {

    private final UUID movieId;
    private final MovieStatus status;

    LockedMovie(UUID movieId, MovieStatus status) {
        this.movieId = Objects.requireNonNull(movieId, "movieId");
        this.status = Objects.requireNonNull(status, "status");
    }

    public UUID movieId() {
        return movieId;
    }

    public MovieStatus status() {
        return status;
    }
}
