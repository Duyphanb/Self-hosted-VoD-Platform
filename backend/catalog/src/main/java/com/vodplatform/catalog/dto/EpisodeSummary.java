package com.vodplatform.catalog.dto;

import com.vodplatform.catalog.persistence.Episode;
import java.time.Instant;
import java.util.UUID;

public record EpisodeSummary(UUID id, UUID titleId, int ordinal, String displayLabel, String name,
                             String videoStatus, Progress progress) {
    public static EpisodeSummary of(Episode episode, String videoStatus, Progress progress) {
        return new EpisodeSummary(episode.getId(), episode.getTitle().getId(), episode.getOrdinal(),
                episode.getDisplayLabel(), episode.getName(), videoStatus, progress);
    }

    public record Progress(UUID episodeId, int currentSeconds, Integer durationSeconds,
                           boolean finished, Instant lastPlayedAt) {
    }
}
