package com.vodplatform.playback.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/** Frozen PlaybackInfo projection, including an explicit null for an unknown duration. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PlaybackInfo(UUID episodeId, UUID titleId, UUID videoAssetId, String status, String hlsManifestUrl,
                           Integer durationSeconds, int resumeSeconds) {
}
