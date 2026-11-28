package com.vodplatform.progress.service;

import com.vodplatform.catalog.dto.EpisodeSummary;
import com.vodplatform.catalog.dto.MovieSummaryResponse;
import com.vodplatform.catalog.dto.PageMeta;
import com.vodplatform.catalog.exception.EpisodeNotFoundException;
import com.vodplatform.catalog.service.EpisodeService;
import com.vodplatform.catalog.service.MovieService;
import com.vodplatform.catalog.service.MovieVisibility;
import com.vodplatform.encoding.persistence.VideoAssetRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ViewerEpisodes {
    private final EpisodeService episodes;
    private final MovieService titles;
    private final JdbcClient jdbc;

    public ViewerEpisodes(EpisodeService episodes, MovieService titles, JdbcClient jdbc) {
        this.episodes = episodes;
        this.titles = titles;
        this.jdbc = jdbc;
    }

    public record EpisodePage(List<EpisodeSummary> items, PageMeta page) {
    }

    public record EpisodeDetail(EpisodeSummary episode, MovieSummaryResponse title, String synopsis) {
    }

    public EpisodePage list(UUID titleId, UUID userId, int page, int size) {
        var found = episodes.visibleEpisodes(titleId, PageRequest.of(page, size));
        var views = projections(found.map(episode -> episode.getId()).getContent(), userId);
        return new EpisodePage(found.stream().map(episode -> EpisodeSummary.of(episode,
                views.get(episode.getId()).videoStatus(), views.get(episode.getId()).progress())).toList(),
                PageMeta.of(found));
    }

    public EpisodeDetail get(UUID episodeId, UUID userId) {
        var episode = episodes.requireVisible(episodeId);
        var title = titles.summaries(List.of(episode.getTitle().getId()), MovieVisibility.PUBLISHED_ONLY).stream()
                .findFirst().orElseThrow(EpisodeNotFoundException::new);
        var view = projections(List.of(episodeId), userId).get(episodeId);
        return new EpisodeDetail(EpisodeSummary.of(episode, view.videoStatus(), view.progress()),
                MovieSummaryResponse.of(title), episode.getSynopsis());
    }

    private record View(UUID episodeId, String videoStatus, EpisodeSummary.Progress progress) {
    }

    private Map<UUID, View> projections(List<UUID> episodeIds, UUID userId) {
        if (episodeIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("""
                SELECT episode.id,
                """ + VideoAssetRepository.EPISODE_VIDEO_STATUS + """
                AS video_status, current_asset.duration_seconds AS current_duration,
                   progress.current_seconds, progress.duration_seconds, progress.finished, progress.last_played_at
                FROM episodes episode
                LEFT JOIN current_episode_assets current_asset ON current_asset.episode_id = episode.id
                LEFT JOIN playback_progress progress ON progress.episode_id = episode.id AND progress.user_id = :userId
                WHERE episode.id IN (:episodeIds)
                """)
                .param("userId", userId)
                .param("episodeIds", episodeIds)
                .query((row, index) -> view(row))
                .list()
                .stream()
                .collect(Collectors.toMap(View::episodeId, Function.identity()));
    }

    /** The caller's progress is normalized against the episode's current asset, as GET /progress does. */
    private static View view(ResultSet row) throws SQLException {
        UUID id = row.getObject("id", UUID.class);
        Timestamp lastPlayed = row.getTimestamp("last_played_at");
        Optional<StoredProgress> stored = Optional.empty();
        if (lastPlayed != null) {
            stored = Optional.of(new StoredProgress(row.getInt("current_seconds"),
                    row.getObject("duration_seconds", Integer.class), row.getBoolean("finished"),
                    lastPlayed.toInstant()));
        }
        Integer currentDuration = row.getObject("current_duration", Integer.class);
        EpisodeSummary.Progress progress = ProgressNormalizer.normalize(stored, currentDuration)
                .progress()
                .map(normalized -> new EpisodeSummary.Progress(id, normalized.currentSeconds(),
                        normalized.durationSeconds(), normalized.finished(), normalized.lastPlayedAt()))
                .orElse(null);
        return new View(id, row.getString("video_status"), progress);
    }
}
