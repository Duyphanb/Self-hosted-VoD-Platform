package com.vodplatform.progress.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ProgressNormalizerTest {

    private static final Instant LAST_PLAYED = Instant.parse("2026-11-01T10:00:00Z");

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, 100})
    void absentRowStaysAbsentAndResumesAtZero(Integer duration) {
        var result = ProgressNormalizer.normalize(Optional.empty(), duration);
        assertThat(result.progress()).isEmpty();
        assertThat(result.resumeSeconds()).isZero();
    }

    @ParameterizedTest
    @CsvSource({
            "-1, 100, false, 0, 0", "0, 100, false, 0, 0",
            "40, 100, false, 40, 40", "90, 100, true, 90, 90",
            "100, 100, false, 100, 0", "100, 100, true, 100, 0",
            "140, 100, true, 100, 0", "140, 100, false, 100, 0",
            "140, 200, true, 140, 140", "0, 0, false, 0, 0",
            "2147483647, 2147483647, true, 2147483647, 0"
    })
    void knownDurationNormalizesRowAndSeparatelyDerivesResume(
            int storedPosition, int duration, boolean finished, int position, int resume) {
        StoredProgress stored = new StoredProgress(storedPosition, 999, finished, LAST_PLAYED);
        var result = ProgressNormalizer.normalize(Optional.of(stored), duration);
        assertThat(result.progress()).contains(new StoredProgress(position, duration, finished, LAST_PLAYED));
        assertThat(result.resumeSeconds()).isEqualTo(resume);
        assertThat(stored.currentSeconds()).isEqualTo(storedPosition);
        assertThat(stored.durationSeconds()).isEqualTo(999);
    }

    @ParameterizedTest
    @CsvSource({"40, false, 40", "90, true, 0", "0, false, 0", "0, true, 0"})
    void unknownDurationPreservesRowIncludingStoredDuration(int position, boolean finished, int resume) {
        StoredProgress stored = new StoredProgress(position, 999, finished, LAST_PLAYED);
        var result = ProgressNormalizer.normalize(Optional.of(stored), null);
        assertThat(result.progress().orElseThrow()).isSameAs(stored);
        assertThat(result.resumeSeconds()).isEqualTo(resume);
    }

    @Test
    void knownDurationReplacesNullStoredDuration() {
        var result = ProgressNormalizer.normalize(
                Optional.of(new StoredProgress(40, null, false, LAST_PLAYED)), 100);
        assertThat(result.progress()).contains(new StoredProgress(40, 100, false, LAST_PLAYED));
        assertThat(result.resumeSeconds()).isEqualTo(40);
    }
}
