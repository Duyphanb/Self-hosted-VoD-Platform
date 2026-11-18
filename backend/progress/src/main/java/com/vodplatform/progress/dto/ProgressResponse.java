package com.vodplatform.progress.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/** Explicit nulls distinguish an absent row from a saved position. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProgressResponse(UUID movieId, int currentSeconds, Integer durationSeconds,
                               boolean finished, Instant lastPlayedAt) {
}
