package com.vodplatform.progress.service;

import java.time.Instant;

/** Immutable progress read model shared with playback and future progress reads. */
public record StoredProgress(int currentSeconds, Integer durationSeconds, boolean finished, Instant lastPlayedAt) {
}
