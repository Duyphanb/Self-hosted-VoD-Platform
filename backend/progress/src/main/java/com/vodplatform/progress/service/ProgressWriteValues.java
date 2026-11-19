package com.vodplatform.progress.service;

/** Pure ADR-011 D10 write derivation, separate from the D11 read normalization. */
public record ProgressWriteValues(int currentSeconds, Integer durationSeconds, boolean finished) {
    private static final int COMPLETION_PERCENT = 90;

    public static ProgressWriteValues derive(int position, boolean ended, Integer duration) {
        if (duration == null || duration < 0) return new ProgressWriteValues(position, null, ended);
        int clamped = ended ? duration : Math.clamp(position, 0, duration);
        boolean finished = ended || (long) clamped * 100 >= (long) duration * COMPLETION_PERCENT;
        return new ProgressWriteValues(clamped, duration, finished);
    }
}
