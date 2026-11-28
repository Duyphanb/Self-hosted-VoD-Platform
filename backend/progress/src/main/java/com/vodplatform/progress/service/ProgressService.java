package com.vodplatform.progress.service;

import com.vodplatform.catalog.service.EpisodeService;
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
    private final EpisodeService episodes;
    private final VideoAssetRepository assets;
    private final ProgressReader reader;
    private final ProgressWrites writes;
    private final WatchHistoryWriter history;
    private final Clock clock;

    public ProgressService(EpisodeService episodes, VideoAssetRepository assets, ProgressReader reader,
                           ProgressWrites writes, WatchHistoryWriter history, Clock clock) {
        this.episodes = episodes;
        this.assets = assets;
        this.reader = reader;
        this.writes = writes;
        this.history = history;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ProgressResponse get(UUID user, UUID episodeId) {
        episodes.requireVisible(episodeId);
        Integer duration = assets.findCurrentReadyByEpisodeId(episodeId).map(VideoAsset::getDurationSeconds).orElse(null);
        return response(episodeId, reader.find(user, episodeId), duration);
    }

    @Transactional
    public ProgressResponse save(UUID user, UUID episodeId, ProgressRequest request) {
        episodes.requireVisible(episodeId);
        var asset = assets.findCurrentReadyByEpisodeId(episodeId).orElseThrow(VideoNotReadyException::new);
        if (!asset.getId().equals(request.videoAssetId())) throw new VideoAssetNotCurrentException();
        var value = ProgressWriteValues.derive(request.currentSeconds(), request.finished(), asset.getDurationSeconds());
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var accepted = writes.upsert(user, episodeId, value, now);
        if (accepted.isPresent()) history.record(user, episodeId, value.finished(), now);
        var stored = accepted.orElseGet(() -> writes.stored(user, episodeId));
        return response(episodeId, Optional.of(stored), asset.getDurationSeconds());
    }

    private static ProgressResponse response(UUID episodeId, Optional<StoredProgress> stored, Integer duration) {
        var row = ProgressNormalizer.normalize(stored, duration).progress()
                .orElseGet(() -> new StoredProgress(0, null, false, null));
        return new ProgressResponse(episodeId, row.currentSeconds(), row.durationSeconds(), row.finished(), row.lastPlayedAt());
    }
}
