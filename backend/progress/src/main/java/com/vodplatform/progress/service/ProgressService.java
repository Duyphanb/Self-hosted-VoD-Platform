package com.vodplatform.progress.service;

import com.vodplatform.catalog.service.MovieService;
import com.vodplatform.catalog.service.MovieVisibility;
import com.vodplatform.encoding.exception.VideoNotReadyException;
import com.vodplatform.encoding.persistence.VideoAsset;
import com.vodplatform.encoding.persistence.VideoAssetRepository;
import com.vodplatform.progress.dto.ProgressRequest;
import com.vodplatform.progress.dto.ProgressResponse;
import com.vodplatform.progress.exception.VideoAssetNotCurrentException;
import com.vodplatform.progress.persistence.ProgressWrites;
import com.vodplatform.progress.persistence.WatchHistoryWriter;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** D9–D12: visibility → current asset → guarded progress → history, in one transaction. */
@Service
public class ProgressService {
    private final MovieService movies;
    private final VideoAssetRepository assets;
    private final ProgressReader reader;
    private final ProgressWrites writes;
    private final WatchHistoryWriter history;
    private final Clock clock;

    public ProgressService(MovieService movies, VideoAssetRepository assets, ProgressReader reader,
                           ProgressWrites writes, WatchHistoryWriter history, Clock clock) {
        this.movies = movies;
        this.assets = assets;
        this.reader = reader;
        this.writes = writes;
        this.history = history;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ProgressResponse get(UUID user, UUID movie) {
        movies.requireVisible(movie, MovieVisibility.PUBLISHED_ONLY);
        Integer duration = assets.findCurrentReadyByMovieId(movie).map(VideoAsset::getDurationSeconds).orElse(null);
        return response(movie, reader.find(user, movie), duration);
    }

    @Transactional
    public ProgressResponse save(UUID user, UUID movie, ProgressRequest request) {
        movies.requireVisible(movie, MovieVisibility.PUBLISHED_ONLY);
        var asset = assets.findCurrentReadyByMovieId(movie).orElseThrow(VideoNotReadyException::new);
        if (!asset.getId().equals(request.videoAssetId())) throw new VideoAssetNotCurrentException();
        var value = ProgressWriteValues.derive(request.currentSeconds(), request.finished(), asset.getDurationSeconds());
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var accepted = writes.upsert(user, movie, value, now);
        if (accepted.isPresent()) history.record(user, movie, value.finished(), now);
        var stored = accepted.orElseGet(() -> writes.stored(user, movie));
        return response(movie, Optional.of(stored), asset.getDurationSeconds());
    }

    private static ProgressResponse response(UUID movie, Optional<StoredProgress> stored, Integer duration) {
        var row = ProgressNormalizer.normalize(stored, duration).progress()
                .orElseGet(() -> new StoredProgress(0, null, false, null));
        return new ProgressResponse(movie, row.currentSeconds(), row.durationSeconds(), row.finished(), row.lastPlayedAt());
    }
}
