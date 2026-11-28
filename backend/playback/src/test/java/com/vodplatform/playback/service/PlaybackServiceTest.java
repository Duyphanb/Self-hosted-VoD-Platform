package com.vodplatform.playback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vodplatform.catalog.exception.EpisodeNotFoundException;
import com.vodplatform.catalog.service.EpisodeService;
import com.vodplatform.catalog.persistence.Episode;
import com.vodplatform.catalog.persistence.Movie;
import com.vodplatform.encoding.exception.VideoNotReadyException;
import com.vodplatform.encoding.persistence.VideoAsset;
import com.vodplatform.encoding.persistence.VideoAssetRepository;
import com.vodplatform.progress.service.ProgressReader;
import com.vodplatform.progress.service.StoredProgress;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Guards the visibility-before-asset-before-own-progress access boundary. */
@ExtendWith(MockitoExtension.class)
class PlaybackServiceTest {

    @Mock private EpisodeService movies;
    @Mock private VideoAssetRepository assets;
    @Mock private ProgressReader progress;
    @Mock private VideoAsset asset;
    @Mock private Episode episode;
    @Mock private Movie title;
    private final UUID movieId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private PlaybackService playback;

    @BeforeEach
    void setUp() {
        playback = new PlaybackService(movies, assets, progress);
    }

    @Test
    void visibilityFailureDoesNotReadAssetsOrProgress() {
        doThrow(new EpisodeNotFoundException()).when(movies).requireVisible(movieId);
        assertThatThrownBy(() -> playback.getPlayback(movieId, userId)).isInstanceOf(EpisodeNotFoundException.class);
        verifyNoInteractions(assets, progress);
    }

    @Test
    void missingCurrentAssetDoesNotReadProgress() {
        when(movies.requireVisible(movieId)).thenReturn(episode);
        when(episode.getTitle()).thenReturn(title);
        when(assets.findCurrentReadyByEpisodeId(movieId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> playback.getPlayback(movieId, userId)).isInstanceOf(VideoNotReadyException.class);
        var order = inOrder(movies, assets);
        order.verify(movies).requireVisible(movieId);
        order.verify(assets).findCurrentReadyByEpisodeId(movieId);
        order.verifyNoMoreInteractions();
        verifyNoInteractions(progress);
    }

    @Test
    void readsCurrentAssetOnceThenOnlyTheCallerMovieProgress() {
        when(movies.requireVisible(movieId)).thenReturn(episode);
        when(episode.getTitle()).thenReturn(title);
        when(assets.findCurrentReadyByEpisodeId(movieId)).thenReturn(Optional.of(asset));
        UUID assetId = UUID.randomUUID();
        when(asset.getId()).thenReturn(assetId);
        when(asset.getDurationSeconds()).thenReturn(100);
        when(progress.find(userId, movieId)).thenReturn(Optional.of(
                new StoredProgress(90, 999, true, Instant.EPOCH)));
        var result = playback.getPlayback(movieId, userId);
        var order = inOrder(movies, assets, progress);
        order.verify(movies).requireVisible(movieId);
        order.verify(assets).findCurrentReadyByEpisodeId(movieId);
        order.verify(progress).find(userId, movieId);
        order.verifyNoMoreInteractions();
        assertThat(result.videoAssetId()).isEqualTo(assetId);
        assertThat(result.durationSeconds()).isEqualTo(100);
        assertThat(result.resumeSeconds()).isEqualTo(90);
    }
}
