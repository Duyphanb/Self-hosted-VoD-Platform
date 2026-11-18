package com.vodplatform.playback.service;

import com.vodplatform.catalog.service.MovieService;
import com.vodplatform.catalog.service.MovieVisibility;
import com.vodplatform.encoding.exception.VideoNotReadyException;
import com.vodplatform.encoding.persistence.VideoAsset;
import com.vodplatform.encoding.persistence.VideoAssetRepository;
import com.vodplatform.encoding.persistence.VideoAssetStatus;
import com.vodplatform.playback.dto.PlaybackInfo;
import com.vodplatform.progress.service.ProgressNormalizer;
import com.vodplatform.progress.service.ProgressReader;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Playback info per ADR-011 D11/D15: visibility, then current READY asset, then caller progress.
 * The read-only transaction normalizes the response without changing stored progress.
 */
@Service
public class PlaybackService {

    private final MovieService movies;
    private final VideoAssetRepository assets;
    private final ProgressReader progress;

    public PlaybackService(MovieService movies, VideoAssetRepository assets, ProgressReader progress) {
        this.movies = movies;
        this.assets = assets;
        this.progress = progress;
    }

    @Transactional(readOnly = true)
    public PlaybackInfo getPlayback(UUID movieId, UUID userId) {
        movies.requireVisible(movieId, MovieVisibility.PUBLISHED_ONLY);
        VideoAsset asset = assets.findCurrentReadyByMovieId(movieId).orElseThrow(VideoNotReadyException::new);
        int resume = ProgressNormalizer.normalize(progress.find(userId, movieId), asset.getDurationSeconds())
                .resumeSeconds();
        return new PlaybackInfo(movieId, asset.getId(), VideoAssetStatus.READY.name(),
                "/hls/video-assets/" + asset.getId() + "/master.m3u8", asset.getDurationSeconds(), resume);
    }
}
