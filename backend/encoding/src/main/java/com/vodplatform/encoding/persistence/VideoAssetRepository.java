package com.vodplatform.encoding.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface VideoAssetRepository extends JpaRepository<VideoAsset, UUID> {
    Page<VideoAsset> findByEpisodeId(UUID episodeId, Pageable pageable);

    Page<VideoAsset> findByStatus(VideoAssetStatus status, Pageable pageable);

    Optional<VideoAsset> findFirstByEpisodeIdOrderByCreatedAtDescIdDesc(UUID episodeId);

    /** Greatest (created_at, id), using database UUID order rather than Java UUID.compareTo. */
    default Optional<VideoAsset> findNewestByEpisodeId(UUID episodeId) {
        return findFirstByEpisodeIdOrderByCreatedAtDescIdDesc(episodeId);
    }

    /** The episode's current asset, as the one shared definition {@code current_episode_assets} selects it. */
    @Query(value = """
            SELECT asset.* FROM video_assets asset
            JOIN current_episode_assets current_asset ON current_asset.asset_id = asset.id
            WHERE current_asset.episode_id = :episodeId
            """, nativeQuery = true)
    Optional<VideoAsset> findCurrentReadyByEpisodeId(UUID episodeId);

    /**
     * The asset only while it is its episode's current asset; HLS checks this on every request. The view is
     * filtered by the asset's own episode, its {@code DISTINCT ON} key, so PostgreSQL reads that episode's index
     * entries instead of ranking every READY asset.
     */
    @Query(value = """
            SELECT asset.* FROM video_assets asset
            WHERE asset.id = :assetId
              AND asset.id = (
                  SELECT current_asset.asset_id FROM current_episode_assets current_asset
                  WHERE current_asset.episode_id = asset.episode_id
              )
            """, nativeQuery = true)
    Optional<VideoAsset> findCurrentReadyById(UUID assetId);

    boolean existsByEpisodeIdAndStatusIn(UUID episodeId, Collection<VideoAssetStatus> statuses);

    /** Advisory check only; the partial unique index {@code ux_video_assets_episode_in_flight} decides races. */
    default boolean existsInFlightByEpisodeId(UUID episodeId) {
        return existsByEpisodeIdAndStatusIn(episodeId, VideoAssetStatus.IN_FLIGHT);
    }

    String EPISODE_VIDEO_STATUS = """
            CASE WHEN current_asset.asset_id IS NOT NULL THEN 'READY' ELSE (
                SELECT asset.status FROM video_assets asset
                WHERE asset.episode_id = episode.id
                ORDER BY asset.created_at DESC, asset.id DESC LIMIT 1
            ) END
            """;

    @Query(value = """
            SELECT CAST(episode.title_id AS varchar(36)),
            """ + EPISODE_VIDEO_STATUS + """
            FROM episodes episode
            LEFT JOIN current_episode_assets current_asset ON current_asset.episode_id = episode.id
            WHERE episode.title_id IN (:movieIds) AND episode.ordinal = 1
            """, nativeQuery = true)
    List<Object[]> findVideoStatusRows(Collection<UUID> movieIds);

    @Query(value = """
            SELECT CAST(episode.id AS varchar(36)),
            """ + EPISODE_VIDEO_STATUS + """
            , current_asset.duration_seconds
            FROM episodes episode
            LEFT JOIN current_episode_assets current_asset ON current_asset.episode_id = episode.id
            WHERE episode.id IN (:episodeIds)
            """, nativeQuery = true)
    List<Object[]> findEpisodeVideoRows(Collection<UUID> episodeIds);

    @Query(value = """
            SELECT CAST(rows.title_id AS varchar(36)), count(*),
                   count(CASE WHEN rows.video_status = 'READY' THEN 1 END),
                   count(CASE WHEN rows.video_status IN ('UPLOADED', 'QUEUED', 'PROCESSING') THEN 1 END)
            FROM (
                SELECT episode.title_id,
            """ + EPISODE_VIDEO_STATUS + """
                AS video_status
                FROM episodes episode
                LEFT JOIN current_episode_assets current_asset ON current_asset.episode_id = episode.id
                WHERE episode.title_id IN (:titleIds) AND episode.status IN (:statuses)
            ) rows
            GROUP BY rows.title_id
            """, nativeQuery = true)
    List<Object[]> findTitleAvailability(Collection<UUID> titleIds, Collection<String> statuses);
}
