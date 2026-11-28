package com.vodplatform.progress.dto;

import com.vodplatform.catalog.dto.EpisodeSummary;
import com.vodplatform.catalog.dto.MovieSummaryResponse;

public record ContinueWatchingItem(Kind kind, MovieSummaryResponse title, EpisodeSummary episode,
                                   EpisodeSummary.Progress progress) {
    public enum Kind { RESUME, NEXT_EPISODE }
}
