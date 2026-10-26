package com.vodplatform.catalog.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Test clock whose instant only changes when a test sets or advances it.
 */
final class MutableClock extends Clock {

    private Instant instant;

    MutableClock(Instant instant) {
        this.instant = instant;
    }

    void set(Instant value) {
        instant = value;
    }

    void advance(Duration duration) {
        instant = instant.plus(duration);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("The test clock is always UTC");
    }
}
