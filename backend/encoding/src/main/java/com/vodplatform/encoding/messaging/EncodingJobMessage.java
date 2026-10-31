package com.vodplatform.encoding.messaging;

import java.util.Objects;
import java.util.UUID;

/** Version-1 IDs-only handoff; storage keys and lifecycle state are loaded from the database. */
public record EncodingJobMessage(UUID encodingJobId, UUID videoAssetId, int attempt) {
    public EncodingJobMessage {
        Objects.requireNonNull(encodingJobId, "encodingJobId");
        Objects.requireNonNull(videoAssetId, "videoAssetId");
        if (attempt < 1) throw new IllegalArgumentException("Encoding attempt must be positive");
    }
}
