package com.vodplatform.catalog.service;

import java.util.UUID;

public final class LockedEpisode {
    private final UUID episodeId;
    private final UUID titleId;

    LockedEpisode(UUID episodeId, UUID titleId) {
        this.episodeId = episodeId;
        this.titleId = titleId;
    }

    public UUID episodeId() { return episodeId; }
    public UUID titleId() { return titleId; }
}
