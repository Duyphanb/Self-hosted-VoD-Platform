package com.vodplatform.encoding.persistence;

import static com.vodplatform.encoding.persistence.EncodingFixtures.T0;
import static com.vodplatform.encoding.persistence.EncodingFixtures.asset;
import static com.vodplatform.encoding.persistence.EncodingFixtures.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.vodplatform.catalog.config.CatalogConfiguration;
import com.vodplatform.catalog.persistence.Movie;
import com.vodplatform.catalog.persistence.MovieStatus;
import com.vodplatform.catalog.service.MovieRowLocks;
import com.vodplatform.encoding.config.EncodingConfiguration;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest
@ContextConfiguration(classes = {EncodingConfiguration.class, CatalogConfiguration.class, MovieRowLocks.class})
class VideoAssetRepositoryTest {

    @Autowired VideoAssetRepository assets;
    @Autowired EncodingJobRepository jobs;
    @Autowired MovieRowLocks locks;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    @Test
    void newestAndCurrentAreEmptyWithoutAssets() {
        assertThat(assets.findNewestByMovieId(uuid(10))).isEmpty();
        assertThat(assets.findCurrentReadyByMovieId(uuid(10))).isEmpty();
    }

    @Test
    void newestUsesTimestampBeforeIdAndIgnoresOtherMovies() {
        assets.saveAllAndFlush(List.of(asset(uuid(99), uuid(10), VideoAssetStatus.READY, T0),
                asset(uuid(1), uuid(10), VideoAssetStatus.FAILED, T0.plusSeconds(1)),
                asset(uuid(100), uuid(20), VideoAssetStatus.QUEUED, T0.plusSeconds(2))));

        assertThat(assets.findNewestByMovieId(uuid(10))).get().extracting(VideoAsset::getId).isEqualTo(uuid(1));
        assertThat(assets.findCurrentReadyByMovieId(uuid(10))).get().extracting(VideoAsset::getId).isEqualTo(uuid(99));
    }

    @Test
    void tiesUseUnsignedDatabaseUuidOrderingForNewestAndCurrent() {
        UUID lower = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
        UUID higher = UUID.fromString("80000000-0000-0000-0000-000000000000");
        assertThat(higher.compareTo(lower)).isNegative();
        assets.saveAllAndFlush(List.of(asset(lower, uuid(10), VideoAssetStatus.READY, T0),
                asset(higher, uuid(10), VideoAssetStatus.READY, T0),
                asset(uuid(1), uuid(10), VideoAssetStatus.READY, T0.minusSeconds(1))));

        assertThat(assets.findNewestByMovieId(uuid(10))).get().extracting(VideoAsset::getId).isEqualTo(higher);
        assertThat(assets.findCurrentReadyByMovieId(uuid(10))).get().extracting(VideoAsset::getId).isEqualTo(higher);
    }

    @ParameterizedTest
    @EnumSource(value = VideoAssetStatus.class, names = {"UPLOADED", "QUEUED", "PROCESSING", "FAILED"})
    void currentIgnoresEveryNewerNonReadyStatus(VideoAssetStatus replacement) {
        assets.saveAllAndFlush(List.of(asset(uuid(1), uuid(10), VideoAssetStatus.READY, T0),
                asset(uuid(2), uuid(10), VideoAssetStatus.READY, T0.plusSeconds(1)),
                asset(uuid(3), uuid(10), replacement, T0.plusSeconds(2))));

        assertThat(assets.findCurrentReadyByMovieId(uuid(10))).get().extracting(VideoAsset::getId).isEqualTo(uuid(2));
        assertThat(assets.findNewestByMovieId(uuid(10))).get().extracting(VideoAsset::getId).isEqualTo(uuid(3));
    }

    @Test
    void currentIsEmptyWhenOnlyFailedOrPendingAssetsExist() {
        assets.saveAllAndFlush(List.of(asset(uuid(1), uuid(10), VideoAssetStatus.FAILED, T0),
                asset(uuid(2), uuid(10), VideoAssetStatus.QUEUED, T0.plusSeconds(1))));

        assertThat(assets.findCurrentReadyByMovieId(uuid(10))).isEmpty();
        // Retry must inspect this newest result, never select an older FAILED row by status.
        assertThat(assets.findNewestByMovieId(uuid(10))).get().extracting(VideoAsset::getStatus).isEqualTo(VideoAssetStatus.QUEUED);
    }

    @Test
    void newestFailedCanBeSelectedForRetryWhileAnOlderReadyRemainsCurrent() {
        assets.saveAllAndFlush(List.of(asset(uuid(1), uuid(10), VideoAssetStatus.READY, T0),
                asset(uuid(2), uuid(10), VideoAssetStatus.FAILED, T0.plusSeconds(1))));

        assertThat(assets.findNewestByMovieId(uuid(10))).get()
                .satisfies(asset -> {
                    assertThat(asset.getId()).isEqualTo(uuid(2));
                    assertThat(asset.getStatus()).isEqualTo(VideoAssetStatus.FAILED);
                });
        assertThat(assets.findCurrentReadyByMovieId(uuid(10))).get().extracting(VideoAsset::getId).isEqualTo(uuid(1));
    }

    @Test
    void movieAndStatusQueriesArePagedAndReturnAssetsInStableOrder() {
        assets.saveAllAndFlush(List.of(asset(uuid(1), uuid(10), VideoAssetStatus.FAILED, T0),
                asset(uuid(2), uuid(10), VideoAssetStatus.FAILED, T0),
                asset(uuid(3), uuid(10), VideoAssetStatus.READY, T0.plusSeconds(1)),
                asset(uuid(4), uuid(20), VideoAssetStatus.FAILED, T0.plusSeconds(2))));

        var first = assets.findByMovieId(uuid(10), PageRequest.of(0, 2, VideoAssetSort.NEWEST_FIRST));
        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getContent()).extracting(VideoAsset::getId).containsExactly(uuid(3), uuid(2));
        assertThat(assets.findByMovieId(uuid(10), PageRequest.of(1, 2, VideoAssetSort.NEWEST_FIRST)).getContent())
                .extracting(VideoAsset::getId).containsExactly(uuid(1));
        var failed = assets.findByStatus(VideoAssetStatus.FAILED, PageRequest.of(0, 2, VideoAssetSort.NEWEST_FIRST));
        assertThat(failed.getTotalElements()).isEqualTo(3);
        assertThat(failed.getContent()).extracting(VideoAsset::getId).containsExactly(uuid(4), uuid(2));
    }

    @ParameterizedTest
    @EnumSource(VideoAssetStatus.class)
    void advisoryInFlightCheckUsesTheExactFrozenPredicate(VideoAssetStatus status) {
        assets.saveAndFlush(asset(uuid(1), uuid(10), status, T0));

        assertThat(assets.existsInFlightByMovieId(uuid(10))).isEqualTo(VideoAssetStatus.IN_FLIGHT.contains(status));
        assertThat(assets.existsInFlightByMovieId(uuid(20))).isFalse();
    }

    @Test
    void creatingAfterTheLockSamplesTheClockAtInsertTimeAndPersistsTheFirstAttempt() {
        MutableClock clock = new MutableClock(T0);
        UUID prePutAssetId = uuid(1);
        Movie movie = new Movie(uuid(10), "Movie", "media-lock", "Description", null, null, MovieStatus.DRAFT, T0);
        entityManager.persist(movie);
        entityManager.flush();
        Instant putFinishedAt = T0.plusSeconds(42).plusNanos(123_456_789);
        clock.set(putFinishedAt);

        var locked = locks.lockForMediaWrite(movie.getId());
        VideoAsset created = VideoAsset.queued(locked, prePutAssetId, "raw", "movie/asset.mp4", clock);
        assertThat(created.isNew()).isTrue();
        assets.saveAndFlush(created);
        // Creating the job after the asset has been flushed is valid within the same transaction.
        EncodingJob job = EncodingJob.firstAttempt(created, uuid(101));
        assertThat(job.isNew()).isTrue();
        jobs.saveAndFlush(job);
        assertThat(created.isNew()).isFalse();
        assertThat(job.isNew()).isFalse();
        entityManager.clear();

        Instant expected = Instant.parse("2026-10-01T08:00:42.123456Z");
        VideoAsset loaded = assets.findById(prePutAssetId).orElseThrow();
        assertThat(loaded.isNew()).isFalse();
        assertThat(loaded.getMovieId()).isEqualTo(movie.getId());
        assertThat(loaded.getCreatedAt()).isEqualTo(expected).isAfter(T0);
        assertThat(loaded.getUpdatedAt()).isEqualTo(expected);
        assertThat(loaded.getStatus()).isEqualTo(VideoAssetStatus.QUEUED);
        assertThat(loaded.getHlsBucket()).isNull();
        assertThat(loaded.getHlsMasterObjectKey()).isNull();
        assertThat(loaded.getDurationSeconds()).isNull();
        assertThat(loaded.getFailureReason()).isNull();
        EncodingJob loadedJob = jobs.findById(uuid(101)).orElseThrow();
        assertThat(loadedJob.isNew()).isFalse();
        assertThat(loadedJob.getVideoAsset().getId()).isEqualTo(loaded.getId());
        assertThat(loadedJob.getAttempt()).isEqualTo(1);
        assertThat(loadedJob.getStatus()).isEqualTo(EncodingJobStatus.QUEUED);
        assertThat(loadedJob.getQueuedAt()).isEqualTo(expected);
        assertThat(loadedJob.getPublishedAt()).isNull();
        assertThat(loadedJob.getStartedAt()).isNull();
        assertThat(loadedJob.getFinishedAt()).isNull();
        assertThat(loadedJob.getErrorMessage()).isNull();
    }

    @Test
    void newAssetWithAnExistingIdFailsInsteadOfMergingOverTheOriginal() {
        assets.saveAndFlush(asset(uuid(1), uuid(10), VideoAssetStatus.FAILED, T0, "original"));
        entityManager.clear();

        Throwable failure = catchThrowable(() -> assets.saveAndFlush(
                asset(uuid(1), uuid(10), VideoAssetStatus.READY, T0.plusSeconds(1), "replacement")));

        assertThat(failure).isNotNull();
        assertThat(jdbc.queryForObject("select raw_object_key from video_assets where id = ?", String.class, uuid(1)))
                .isEqualTo("original");
    }

    @Test
    void newJobWithAnExistingIdFailsInsteadOfChangingItsAsset() {
        VideoAsset firstAsset = asset(uuid(1), uuid(10), VideoAssetStatus.QUEUED, T0);
        VideoAsset secondAsset = asset(uuid(2), uuid(20), VideoAssetStatus.QUEUED, T0);
        assets.saveAllAndFlush(List.of(firstAsset, secondAsset));
        jobs.saveAndFlush(EncodingJob.firstAttempt(firstAsset, uuid(101)));
        entityManager.clear();

        Throwable failure = catchThrowable(() -> jobs.saveAndFlush(EncodingJob.firstAttempt(secondAsset, uuid(101))));

        assertThat(failure).isNotNull();
        assertThat(jdbc.queryForObject("select video_asset_id from encoding_jobs where id = ?", UUID.class, uuid(101)))
                .isEqualTo(firstAsset.getId());
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) { this.now = now; }
        void set(Instant value) { now = value; }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }
}
