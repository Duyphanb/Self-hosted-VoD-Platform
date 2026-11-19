package com.vodplatform.progress.dto;

import com.vodplatform.catalog.dto.MovieSummaryResponse;

/** A visible catalog card and the same normalized progress returned by GET /progress. */
public record ContinueWatchingItem(MovieSummaryResponse movie, ProgressResponse progress) {
}
