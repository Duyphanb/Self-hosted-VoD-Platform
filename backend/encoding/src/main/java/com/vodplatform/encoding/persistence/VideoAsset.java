package com.vodplatform.encoding.persistence;

import com.vodplatform.catalog.service.LockedMovie;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.Length;
import org.springframework.data.domain.Persistable;

/**
 * One new asset per upload. Lifecycle transitions belong to conditional updates, not setters.
 * The movie reference is a UUID because catalog's aggregate is internal to that module.
 */
@Entity
@Table(name = "video_assets", uniqueConstraints = {
        @UniqueConstraint(name = EncodingConstraints.VIDEO_ASSETS_RAW_OBJECT,
                columnNames = {"raw_bucket", "raw_object_key"}),
        // V1 makes this partial when the master key is non-null. Null keys do not collide here.
        @UniqueConstraint(name = EncodingConstraints.VIDEO_ASSETS_HLS_MASTER,
                columnNames = {"hls_bucket", "hls_master_object_key"})
}, indexes = {
        @Index(name = "ix_video_assets_movie_id", columnList = "movie_id"),
        @Index(name = "ix_video_assets_status", columnList = "status")
})
// Never declare ux_video_assets_movie_in_flight here: H2 cannot express its WHERE predicate.
public class VideoAsset implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "movie_id", nullable = false, updatable = false)
    private UUID movieId;

    @Column(name = "raw_bucket", nullable = false, length = 100, updatable = false)
    private String rawBucket;

    @Column(name = "raw_object_key", nullable = false, length = 512, updatable = false)
    private String rawObjectKey;

    @Column(name = "hls_bucket", length = 100)
    private String hlsBucket;

    @Column(name = "hls_master_object_key", length = 512)
    private String hlsMasterObjectKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private VideoAssetStatus status;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "codec", length = 100)
    private String codec;

    @Column(name = "bitrate")
    private Integer bitrate;

    @Column(name = "failure_reason", length = Length.LONG32)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean newEntity = true;

    protected VideoAsset() {
    }

    VideoAsset(UUID id, UUID movieId, String rawBucket, String rawObjectKey, String hlsBucket,
               String hlsMasterObjectKey, VideoAssetStatus status, Integer durationSeconds,
               Integer width, Integer height, String codec, Integer bitrate, String failureReason,
               Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.movieId = Objects.requireNonNull(movieId, "movieId");
        this.rawBucket = Objects.requireNonNull(rawBucket, "rawBucket");
        this.rawObjectKey = Objects.requireNonNull(rawObjectKey, "rawObjectKey");
        this.hlsBucket = hlsBucket;
        this.hlsMasterObjectKey = hlsMasterObjectKey;
        this.status = Objects.requireNonNull(status, "status");
        this.durationSeconds = durationSeconds;
        this.width = width;
        this.height = height;
        this.codec = codec;
        this.bitrate = bitrate;
        this.failureReason = failureReason;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /**
     * Call in the asset/job insert transaction, after raw PUT and the per-movie lock. The caller
     * supplies the shared appClock; time is sampled here, never alongside pre-PUT ID generation.
     */
    public static VideoAsset queued(LockedMovie lock, UUID id, String rawBucket, String rawObjectKey, Clock clock) {
        Objects.requireNonNull(lock, "lock");
        Instant now = Objects.requireNonNull(clock, "clock").instant().truncatedTo(ChronoUnit.MICROS);
        return new VideoAsset(id, lock.movieId(), rawBucket, rawObjectKey, null, null,
                VideoAssetStatus.QUEUED, null, null, null, null, null, null, now, now);
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
    public UUID getMovieId() { return movieId; }
    public String getRawBucket() { return rawBucket; }
    public String getRawObjectKey() { return rawObjectKey; }
    public String getHlsBucket() { return hlsBucket; }
    public String getHlsMasterObjectKey() { return hlsMasterObjectKey; }
    public VideoAssetStatus getStatus() { return status; }
    public Integer getDurationSeconds() { return durationSeconds; }
    public Integer getWidth() { return width; }
    public Integer getHeight() { return height; }
    public String getCodec() { return codec; }
    public Integer getBitrate() { return bitrate; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
