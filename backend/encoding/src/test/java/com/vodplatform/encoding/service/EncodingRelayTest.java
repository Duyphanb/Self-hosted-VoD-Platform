package com.vodplatform.encoding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.vodplatform.encoding.messaging.EncodingJobPublisher;
import com.vodplatform.encoding.persistence.EncodingJobRepository;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

class EncodingRelayTest {

    @Test
    void queryUsesTwiceConfirmTimeoutAndDeferredLogUsesOnlyExceptionClass() {
        var jobs = mock(EncodingJobRepository.class);
        var service = mock(EncodingJobService.class);
        var publisher = mock(EncodingJobPublisher.class);
        Instant now = Instant.parse("2026-11-01T00:00:00Z");
        var relay = new EncodingRelay(jobs, service, publisher, Clock.fixed(now, ZoneOffset.UTC));
        when(publisher.confirmTimeout()).thenReturn(Duration.ofSeconds(5));
        when(jobs.pending(eq(now.minusSeconds(10)), any()))
                .thenThrow(new IllegalStateException("secret\nforged"));
        Logger logger = (Logger) LoggerFactory.getLogger(EncodingRelay.class);
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start();
        logger.addAppender(capture);
        try {
            relay.relayOnce();
            assertThat(capture.list).hasSize(1);
            var event = capture.list.getFirst();
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .isEqualTo("encoding relay deferred exceptionClass=IllegalStateException");
            assertThat(event.getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(capture);
            capture.stop();
        }
    }
}
