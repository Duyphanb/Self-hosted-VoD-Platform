package com.vodplatform.progress.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** ADR-011 D10: client duration is accepted for compatibility and ignored. */
public record ProgressRequest(@NotNull UUID videoAssetId, @NotNull @Min(0) Integer currentSeconds,
                              @Min(0) Integer durationSeconds, @NotNull Boolean finished) {
}
