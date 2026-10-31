package com.vodplatform.encoding.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.Length;
import org.springframework.data.domain.Persistable;

/** Durable job record; published_at is outbox state, independent of lifecycle status. */
@Entity
@Table(name = "encoding_jobs", uniqueConstraints = @UniqueConstraint(
        name = EncodingConstraints.ENCODING_JOBS_ASSET_ATTEMPT, columnNames = {"video_asset_id", "attempt"}),
        indexes = {
                @Index(name = "ix_encoding_jobs_video_asset_id", columnList = "video_asset_id"),
                @Index(name = "ix_encoding_jobs_status", columnList = "status")
        })
public class EncodingJob implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "video_asset_id", nullable = false, updatable = false)
    private VideoAsset videoAsset;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EncodingJobStatus status;

    @Column(name = "attempt", nullable = false, updatable = false)
    private int attempt;

    @Column(name = "error_message", length = Length.LONG32)
    private String errorMessage;

    @Column(name = "queued_at", nullable = false, updatable = false)
    private Instant queuedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Transient
    private boolean newEntity = true;

    protected EncodingJob() {
    }

    private EncodingJob(VideoAsset videoAsset, UUID id) {
        this.id = Objects.requireNonNull(id, "id");
        this.videoAsset = Objects.requireNonNull(videoAsset, "videoAsset");
        status = EncodingJobStatus.QUEUED;
        attempt = 1;
        queuedAt = videoAsset.getCreatedAt();
    }

    /** Insert together with a newly queued asset; publication is recorded conditionally in 4.5. */
    public static EncodingJob firstAttempt(VideoAsset asset, UUID id) {
        Objects.requireNonNull(asset, "asset");
        if (asset.getStatus() != VideoAssetStatus.QUEUED) {
            throw new IllegalArgumentException("First attempt requires a QUEUED asset");
        }
        return new EncodingJob(asset, id);
    }

    @PostPersist
    @PostLoad
    private void markPersisted() {
        newEntity = false;
    }

    @Override
    public boolean isNew() { return newEntity; }

    @Override
    public UUID getId() { return id; }
    public VideoAsset getVideoAsset() { return videoAsset; }
    public EncodingJobStatus getStatus() { return status; }
    public int getAttempt() { return attempt; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getQueuedAt() { return queuedAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
}
