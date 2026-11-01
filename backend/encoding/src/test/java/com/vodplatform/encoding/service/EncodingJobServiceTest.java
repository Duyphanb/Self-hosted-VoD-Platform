package com.vodplatform.encoding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.vodplatform.catalog.exception.MovieNotFoundException;
import com.vodplatform.catalog.service.LockedMovie;
import com.vodplatform.catalog.service.MovieRowLocks;
import com.vodplatform.encoding.exception.UploadCommitUnknownException;
import com.vodplatform.encoding.exception.UploadDatabaseException;
import com.vodplatform.encoding.messaging.EncodingJobMessage;
import com.vodplatform.encoding.messaging.EncodingJobPublisher;
import com.vodplatform.encoding.messaging.PublishOutcome;
import com.vodplatform.encoding.persistence.EncodingJobRepository;
import com.vodplatform.encoding.persistence.VideoAssetRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

class EncodingJobServiceTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(EncodingJobService.class);

    private final ListAppender<ILoggingEvent> capture = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        capture.start();
        logger.addAppender(capture);
    }

    @AfterEach
    void detachLogs() {
        logger.detachAppender(capture);
        capture.stop();
    }

    final VideoAssetRepository assets = mock(VideoAssetRepository.class);

    final EncodingJobRepository jobs = mock(EncodingJobRepository.class);

    final MovieRowLocks locks = mock(MovieRowLocks.class);

    final EncodingJobPublisher publisher = mock(EncodingJobPublisher.class);

    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);

    final UUID movie = UUID.randomUUID(), asset = UUID.randomUUID(), job = UUID.randomUUID();

    final Clock clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);

    EncodingJobService service() {
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        LockedMovie lock = mock(LockedMovie.class);
        when(lock.movieId()).thenReturn(movie);
        when(locks.lockForMediaWrite(movie)).thenReturn(lock);
        when(assets.save(any())).thenAnswer(call -> call.getArgument(0));
        return new EncodingJobService(assets, jobs, locks, publisher, clock, manager);
    }

    @Test
    void ambiguousCommitReadsBackUnderMovieLockAndOnlyReturnsWhenBothRowsExist() {
        var service = service();
        doThrow(new IllegalStateException("commit response lost"))
                .doNothing()
                .when(manager)
                .commit(any());
        when(assets.existsById(asset)).thenReturn(true);
        when(jobs.existsById(job)).thenReturn(true);
        service.commitNewUpload(movie, asset, job, "vod-raw", "owned-key");
        var order = inOrder(locks, assets, jobs);
        order.verify(locks).lockForMediaWrite(movie);
        order.verify(assets).existsInFlightByMovieId(movie);
        order.verify(assets).save(any());
        order.verify(jobs).saveAndFlush(any());
        order.verify(locks).lockForMediaWrite(movie);
        order.verify(assets).existsById(asset);
        order.verify(jobs).existsById(job);
        assertSafeWarn("upload commit resolved outcome=COMMITTED");
        verifyNoInteractions(publisher);
    }

    @Test
    void absentRowsAndUnknownReadbacksAreDistinctAndSafe() {
        var service = service();
        doThrow(new IllegalStateException("commit response lost"))
                .doNothing()
                .when(manager)
                .commit(any());
        assertThatThrownBy(() -> service.commitNewUpload(movie, asset, job, "vod-raw", "owned-key"))
                .isInstanceOf(UploadDatabaseException.class)
                .hasNoCause();
        assertSafeWarn("upload commit resolved outcome=ROLLED_BACK");
        doThrow(new IllegalStateException("commit response lost"))
                .doNothing()
                .when(manager)
                .commit(any());
        when(assets.existsById(asset)).thenReturn(true);
        when(jobs.existsById(job)).thenReturn(false);
        assertThatThrownBy(() -> service.commitNewUpload(movie, asset, job, "vod-raw", "owned-key"))
                .isInstanceOf(UploadCommitUnknownException.class)
                .hasNoCause();
    }

    @Test
    void confirmedButMarkFailureRemainsEligibleForDuplicatePublication() {
        var service = service();
        var message = new EncodingJobMessage(job, asset, 1);
        when(publisher.publish(any(), any())).thenReturn(new PublishOutcome.Confirmed());
        when(jobs.markPublished(eq(job), any()))
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(0);
        assertThat(service.publish(message, Optional.empty())).isFalse();
        assertThat(service.publish(message, Optional.empty())).isTrue();
        verify(publisher, times(2)).publish(eq(message), eq(Optional.empty()));
        verify(jobs, times(2)).markPublished(job, clock.instant());
    }

    private void assertSafeWarn(String prefix) {
        assertThat(capture.list)
                .anySatisfy(
                        event -> {
                            assertThat(event.getLevel()).isEqualTo(Level.WARN);
                            assertThat(event.getFormattedMessage())
                                    .startsWith(prefix)
                                    .contains(
                                            "movieId=" + movie,
                                            "videoAssetId=" + asset,
                                            "encodingJobId=" + job)
                                    .doesNotContain("owned-key", "vod-raw", "response lost");
                            assertThat(event.getThrowableProxy()).isNull();
                        });
    }

    @Test
    void precheckRepositoryFailureIsSafeAndMissingMoviePassesThrough() {
        var service = service();
        when(assets.existsInFlightByMovieId(movie))
                .thenThrow(new IllegalStateException("secret DB diagnostic"));
        assertThatThrownBy(() -> service.precheck(movie))
                .isInstanceOf(UploadDatabaseException.class)
                .hasNoCause();
        doThrow(new MovieNotFoundException()).when(locks).lockForMediaWrite(movie);
        assertThatThrownBy(() -> service.precheck(movie))
                .isInstanceOf(MovieNotFoundException.class);
        verifyNoInteractions(publisher);
    }

    @Test
    void relayRepublishLogsActualOutcomeWithIdsOnly() {
        var service = service();
        var message = new EncodingJobMessage(job, asset, 1);
        when(publisher.publish(any(), any()))
                .thenReturn(new PublishOutcome.Returned(312, "secret broker text"));
        assertThat(service.republish(message)).isFalse();
        assertThat(capture.list).hasSize(1);
        var event = capture.list.getFirst();
        assertThat(event.getFormattedMessage())
                .isEqualTo(
                        "encoding relay republish encodingJobId="
                                + job
                                + " videoAssetId="
                                + asset
                                + " attempt=1 outcome=Returned");
        assertThat(event.getThrowableProxy()).isNull();
    }
}
