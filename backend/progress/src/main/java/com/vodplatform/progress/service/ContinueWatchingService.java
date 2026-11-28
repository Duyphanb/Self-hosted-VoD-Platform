package com.vodplatform.progress.service;

import com.vodplatform.catalog.dto.EpisodeSummary;
import com.vodplatform.catalog.dto.MovieSummaryResponse;
import com.vodplatform.catalog.service.MovieService;
import com.vodplatform.catalog.service.MovieVisibility;
import com.vodplatform.progress.dto.ContinueWatchingItem;
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
                        selected.stream().map(ContinueWatchingReader.Selection::titleId).toList(),
                        MovieVisibility.PUBLISHED_ONLY).stream()
                .collect(Collectors.toMap(summary -> summary.id(), MovieSummaryResponse::of));
        // Rechecking catalog visibility can remove a movie hidden after the selection snapshot.
        return selected.stream().filter(row -> summaries.containsKey(row.titleId())).map(row -> {
            EpisodeSummary.Progress progress = ProgressNormalizer.normalize(Optional.ofNullable(row.progress()),
                    row.currentDuration()).progress().map(p -> new EpisodeSummary.Progress(row.episodeId(),
                    p.currentSeconds(), p.durationSeconds(), p.finished(), p.lastPlayedAt())).orElse(null);
            var episode = new EpisodeSummary(row.episodeId(), row.titleId(), row.ordinal(), row.displayLabel(),
                    row.name(), "READY", progress);
            var kind = row.advance() ? ContinueWatchingItem.Kind.NEXT_EPISODE : ContinueWatchingItem.Kind.RESUME;
            return new ContinueWatchingItem(kind, summaries.get(row.titleId()), episode, progress);
        }).toList();
    }
}
