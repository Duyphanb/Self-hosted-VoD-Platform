package com.vodplatform.catalog.service;

import java.util.Objects;
import java.util.UUID;

/**
 * The episode identity and its title, read under the caller's transaction lock. Only
 * {@link EpisodeRowLocks} creates this token; use it inside that same transaction.
 */
public final class LockedEpisode {

    private final UUID episodeId;
    private final UUID titleId;

    LockedEpisode(UUID episodeId, UUID titleId) {
        this.episodeId = Objects.requireNonNull(episodeId, "episodeId");
        this.titleId = Objects.requireNonNull(titleId, "titleId");
    }

    public UUID episodeId() {
        return episodeId;
    }

    public UUID titleId() {
        return titleId;
    }
}
