package com.vodplatform.catalog.service;

import com.vodplatform.catalog.dto.EpisodeSummary;
import java.util.UUID;

/** Viewer progress for the single episode embedded in a film detail. */
public interface EpisodeProgressLookup {
    EpisodeSummary.Progress find(UUID userId, UUID episodeId);
}
