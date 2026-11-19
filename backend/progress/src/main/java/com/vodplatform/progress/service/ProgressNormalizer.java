package com.vodplatform.progress.service;

import java.util.Objects;
import java.util.Optional;

/** Pure ADR-011 D11 read rules. Completion is preserved, never recomputed by a read. */
public final class ProgressNormalizer {

    private ProgressNormalizer() {
    }

    /** A missing row stays absent; callers can apply their endpoint's empty-state DTO. */
    public record Result(Optional<StoredProgress> progress, int resumeSeconds) {
    }

    /**
     * ADR-011 D11 continue-watching membership. Unknown or nonpositive duration cannot establish
     * that the normalized position is below the end. The SQL selection has a tested twin of this
     * predicate so filtering happens before its limit.
     */
    public static boolean isResumable(StoredProgress stored, Integer currentDurationSeconds) {
        Objects.requireNonNull(stored, "stored");
        if (stored.finished() || currentDurationSeconds == null || currentDurationSeconds <= 0) {
            return false;
        }
        int position = normalize(Optional.of(stored), currentDurationSeconds)
                .progress().orElseThrow().currentSeconds();
        return position > 0 && position < currentDurationSeconds;
    }

    public static Result normalize(Optional<StoredProgress> stored, Integer currentDurationSeconds) {
        Objects.requireNonNull(stored, "stored");
        if (stored.isEmpty()) {
            return new Result(Optional.empty(), 0);
        }
        StoredProgress row = stored.orElseThrow();
        if (currentDurationSeconds == null || currentDurationSeconds < 0) {
            return new Result(stored, row.finished() ? 0 : row.currentSeconds());
        }
        int position = Math.clamp(row.currentSeconds(), 0, currentDurationSeconds);
        StoredProgress normalized = new StoredProgress(position, currentDurationSeconds,
                row.finished(), row.lastPlayedAt());
        return new Result(Optional.of(normalized), position == currentDurationSeconds ? 0 : position);
    }
}
