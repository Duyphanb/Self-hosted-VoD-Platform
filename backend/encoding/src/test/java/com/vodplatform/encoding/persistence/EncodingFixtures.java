package com.vodplatform.encoding.persistence;

import java.time.Instant;
import java.util.UUID;

/** Explicit historical rows for repository tests; production creation is only through the lock. */
final class EncodingFixtures {
    static final Instant T0 = Instant.parse("2026-10-01T08:00:00Z");

    private EncodingFixtures() {
    }

    static UUID uuid(long value) {
        return new UUID(0L, value);
    }

    static VideoAsset asset(UUID id, UUID movieId, VideoAssetStatus status, Instant createdAt) {
        return asset(id, movieId, status, createdAt, "raw/" + id);
    }

    static VideoAsset asset(UUID id, UUID movieId, VideoAssetStatus status, Instant createdAt, String rawKey) {
        boolean ready = status == VideoAssetStatus.READY;
        return new VideoAsset(id, movieId, "raw", rawKey, ready ? "hls" : null,
                ready ? "video-assets/" + id + "/attempt-1/master.m3u8" : null, status,
                ready ? 120 : null, ready ? 1920 : null, ready ? 1080 : null,
                ready ? "h264" : null, ready ? 4_000_000 : null,
                status == VideoAssetStatus.FAILED ? "ENCODING_FAILED" : null, createdAt, createdAt);
    }
}
