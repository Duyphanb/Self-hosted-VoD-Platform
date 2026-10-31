package com.vodplatform.encoding.persistence;

import java.util.Set;

/** Asset lifecycle values from V1. UPLOADED is reserved and never committed by upload. */
public enum VideoAssetStatus {
    UPLOADED, QUEUED, PROCESSING, READY, FAILED;

    public static final Set<VideoAssetStatus> IN_FLIGHT = Set.of(UPLOADED, QUEUED, PROCESSING);
}
