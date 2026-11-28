package com.vodplatform.progress.service;

import com.vodplatform.catalog.dto.EpisodeSummary;
import com.vodplatform.catalog.service.EpisodeProgressLookup;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class FilmEpisodeProgress implements EpisodeProgressLookup {
    private final ProgressService progress;

    public FilmEpisodeProgress(ProgressService progress) {
        this.progress = progress;
    }

    @Override
    public EpisodeSummary.Progress find(UUID userId, UUID episodeId) {
        var row = progress.get(userId, episodeId);
        return row.lastPlayedAt() == null ? null : new EpisodeSummary.Progress(episodeId, row.currentSeconds(),
                row.durationSeconds(), row.finished(), row.lastPlayedAt());
    }
}
