package com.vodplatform.catalog.service;

import com.vodplatform.catalog.persistence.MovieStatus;

/**
 * Which movie statuses a caller may see (ADR-009, {@code SECURITY.md#movie-visibility}). This is
 * the single place that encodes the status rule.
 */
public enum MovieVisibility {

    /**
     * Only {@code PUBLISHED} movies. Applies to every caller without {@code ROLE_ADMIN}, and to
     * admins everywhere except the admin exceptions below (for example playback, HLS, progress,
     * rating and watchlist).
     */
    PUBLISHED_ONLY,

    /**
     * Every status. Only for {@code ROLE_ADMIN} on {@code GET}, {@code PUT} and {@code DELETE
     * /api/v1/movies/{movieId}} and under {@code /api/v1/admin/**}.
     */
    ANY_STATUS;

    public boolean includes(MovieStatus status) {
        return this == ANY_STATUS || status == MovieStatus.PUBLISHED;
    }
}
