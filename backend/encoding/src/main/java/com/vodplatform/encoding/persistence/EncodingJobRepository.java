package com.vodplatform.encoding.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface EncodingJobRepository extends JpaRepository<EncodingJob, UUID> {
    interface PendingJob {
        UUID getId();

        UUID getVideoAssetId();

        int getAttempt();
    }

    @Query(
            "select j.id as id, j.videoAsset.id as videoAssetId, j.attempt as attempt from"
                + " EncodingJob j where j.status ="
                + " com.vodplatform.encoding.persistence.EncodingJobStatus.QUEUED and j.publishedAt"
                + " is null and j.queuedAt <= :cutoff order by j.queuedAt, j.id")
    List<PendingJob> pending(@Param("cutoff") Instant cutoff, Pageable limit);

    // Deliberately neither reads nor writes status: a worker may already have finished.
    @Modifying
    @Query(
            "update EncodingJob j set j.publishedAt = :at where j.id = :id and j.publishedAt is"
                    + " null")
    int markPublished(@Param("id") UUID id, @Param("at") Instant at);
}
