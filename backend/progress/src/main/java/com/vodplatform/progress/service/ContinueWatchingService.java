package com.vodplatform.progress.service;

import com.vodplatform.catalog.dto.MovieSummaryResponse;
import com.vodplatform.catalog.service.MovieService;
import com.vodplatform.catalog.service.MovieVisibility;
import com.vodplatform.progress.dto.ContinueWatchingItem;
import com.vodplatform.progress.dto.ProgressResponse;
import com.vodplatform.progress.persistence.ContinueWatchingReader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns the user-facing list, including its published-only visibility for administrators. */
@Service
public class ContinueWatchingService {
    private final ContinueWatchingReader reader;
    private final MovieService catalog;

    public ContinueWatchingService(ContinueWatchingReader reader, MovieService catalog) {
        this.reader = reader;
        this.catalog = catalog;
    }

    @Transactional(readOnly = true)
    public List<ContinueWatchingItem> get(UUID userId) {
        var selected = reader.find(userId, MovieVisibility.PUBLISHED_ONLY);
        Map<UUID, MovieSummaryResponse> summaries = catalog.summaries(
                        selected.stream().map(ContinueWatchingReader.Selection::movieId).toList(),
                        MovieVisibility.PUBLISHED_ONLY).stream()
                .collect(Collectors.toMap(summary -> summary.id(), MovieSummaryResponse::of));
        // Rechecking catalog visibility can remove a movie hidden after the selection snapshot.
        return selected.stream().filter(row -> summaries.containsKey(row.movieId())).map(row -> {
            StoredProgress normalized = ProgressNormalizer.normalize(
                    Optional.of(row.progress()), row.currentDuration()).progress().orElseThrow();
            return new ContinueWatchingItem(summaries.get(row.movieId()), new ProgressResponse(
                    row.movieId(), normalized.currentSeconds(), normalized.durationSeconds(),
                    normalized.finished(), normalized.lastPlayedAt()));
        }).toList();
    }
}
