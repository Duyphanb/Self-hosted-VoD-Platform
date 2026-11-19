package com.vodplatform.progress.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.vodplatform.catalog.dto.MovieSummary;
import com.vodplatform.catalog.persistence.MovieStatus;
import com.vodplatform.catalog.service.MovieService;
import com.vodplatform.catalog.service.MovieVisibility;
import com.vodplatform.progress.persistence.ContinueWatchingReader;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContinueWatchingServiceTest {
    @Test
    void dropsMoviesHiddenBetweenReadsAndKeepsSelectionOrderAndNormalizedDuration() {
        var reader = mock(ContinueWatchingReader.class);
        var catalog = mock(MovieService.class);
        var service = new ContinueWatchingService(reader, catalog);
        UUID user = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID hidden = UUID.randomUUID();
        UUID last = UUID.randomUUID();
        Instant played = Instant.parse("2026-11-01T00:00:00Z");
        StoredProgress stored = new StoredProgress(40, 999, false, played);
        when(reader.find(user, MovieVisibility.PUBLISHED_ONLY)).thenReturn(List.of(
                new ContinueWatchingReader.Selection(first, stored, 100),
                new ContinueWatchingReader.Selection(hidden, stored, 100),
                new ContinueWatchingReader.Selection(last, stored, 200)));
        // A concurrent archive made the middle movie disappear from the catalog batch read.
        when(catalog.summaries(List.of(first, hidden, last), MovieVisibility.PUBLISHED_ONLY))
                .thenReturn(List.of(summary(last), summary(first)));
        var result = service.get(user);
        assertThat(result).extracting(item -> item.movie().id()).containsExactly(first, last);
        assertThat(result).extracting(item -> item.progress().movieId()).containsExactly(first, last);
        assertThat(result).extracting(item -> item.progress().durationSeconds()).containsExactly(100, 200);
        assertThat(result).allSatisfy(item -> {
            assertThat(item.progress().currentSeconds()).isEqualTo(40);
            assertThat(item.progress().lastPlayedAt()).isEqualTo(played);
            assertThat(item.progress().finished()).isFalse();
        });
    }

    private MovieSummary summary(UUID id) {
        return new MovieSummary(id, "Fixture", "fixture-" + id, "Fixture", 2026,
                List.of("Action"), MovieStatus.PUBLISHED, "READY");
    }
}
