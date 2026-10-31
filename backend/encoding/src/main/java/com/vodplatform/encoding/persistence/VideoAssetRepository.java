package com.vodplatform.encoding.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VideoAssetRepository extends JpaRepository<VideoAsset, UUID> {

    Page<VideoAsset> findByMovieId(UUID movieId, Pageable pageable);

    Page<VideoAsset> findByStatus(VideoAssetStatus status, Pageable pageable);

    Optional<VideoAsset> findFirstByMovieIdOrderByCreatedAtDescIdDesc(UUID movieId);

    Optional<VideoAsset> findFirstByMovieIdAndStatusOrderByCreatedAtDescIdDesc(UUID movieId, VideoAssetStatus status);

    /** Greatest (created_at, id), using database UUID order rather than Java UUID.compareTo. */
    default Optional<VideoAsset> findNewestByMovieId(UUID movieId) {
        return findFirstByMovieIdOrderByCreatedAtDescIdDesc(movieId);
    }

    /** Greatest READY tuple. The descriptive method name alone is not a derived-query filter. */
    default Optional<VideoAsset> findCurrentReadyByMovieId(UUID movieId) {
        return findFirstByMovieIdAndStatusOrderByCreatedAtDescIdDesc(movieId, VideoAssetStatus.READY);
    }

    boolean existsByMovieIdAndStatusIn(UUID movieId, Collection<VideoAssetStatus> statuses);

    /** Advisory check only; V5's partial unique index is authoritative for races. */
    default boolean existsInFlightByMovieId(UUID movieId) {
        return existsByMovieIdAndStatusIn(movieId, VideoAssetStatus.IN_FLIGHT);
    }

    /** UUID text keeps the native result stable across H2's binary and PostgreSQL's UUID JDBC types. */
    @Query(value = """
            SELECT CAST(a.movie_id AS varchar(36)), a.status
            FROM video_assets a
            WHERE a.movie_id IN (:movieIds)
              AND NOT EXISTS (
                  SELECT 1 FROM video_assets b
                  WHERE b.movie_id = a.movie_id
                    AND (CASE WHEN b.status = 'READY' THEN 1 ELSE 0 END, b.created_at, b.id)
                      > (CASE WHEN a.status = 'READY' THEN 1 ELSE 0 END, a.created_at, a.id))
            """, nativeQuery = true)
    List<Object[]> findVideoStatusRows(@Param("movieIds") Collection<UUID> movieIds);
}
