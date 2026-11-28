package com.vodplatform.catalog.service;

import com.vodplatform.catalog.persistence.MovieStatus;
import java.util.Arrays;
import java.util.List;

/**
 * Which movie statuses a caller may see (ADR-009, {@code SECURITY.md#title-and-episode-visibility}). This is
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
     * /api/v1/admin/titles/{titleId}} and other admin routes.
     */
    ANY_STATUS;

    public boolean includes(MovieStatus status) {
        return this == ANY_STATUS || status == MovieStatus.PUBLISHED;
    }

    /** Statuses to bind in set-based reads; {@link #includes} remains the catalog's one rule. */
    public List<MovieStatus> statuses() {
        return Arrays.stream(MovieStatus.values()).filter(this::includes).toList();
    }
}
