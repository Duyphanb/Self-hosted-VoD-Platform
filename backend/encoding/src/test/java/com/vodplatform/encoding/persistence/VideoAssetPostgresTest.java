package com.vodplatform.encoding.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.vodplatform.catalog.config.CatalogConfiguration;
import com.vodplatform.catalog.service.LockedMovie;
import com.vodplatform.catalog.service.MovieRowLocks;
import com.vodplatform.encoding.config.EncodingConfiguration;
import com.vodplatform.encoding.service.EncodingVideoStatusLookup;
import com.vodplatform.shared.time.TimeConfiguration;
import jakarta.persistence.EntityManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Run only against a disposable PostgreSQL database already migrated through V5. Locally opt in
 * with ENCODING_TEST_POSTGRES_URL; CI also sets ENCODING_TEST_POSTGRES_REQUIRED=true so a missing
 * URL fails rather than silently skipping. Credentials are separate from the URL. Only this
 * class's random movie fixtures (and their cascading assets/jobs) are removed afterwards.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@ContextConfiguration(classes = {EncodingConfiguration.class, CatalogConfiguration.class,
        MovieRowLocks.class, EncodingVideoStatusLookup.class, TimeConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIf("postgresConfigured")
class VideoAssetPostgresTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");
    private static final long TIMEOUT_SECONDS = 20;

    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private VideoAssetRepository assets;
    @Autowired private EncodingJobRepository jobs;
    @Autowired private MovieRowLocks locks;
    @Autowired private EncodingVideoStatusLookup lookup;
    @Autowired private Clock appClock;
    @Autowired private EntityManager entityManager;

    private final List<UUID> movies = new ArrayList<>();
    private JdbcTemplate jdbc;
    private ExecutorService executor;

    static boolean postgresConfigured() {
        return required() || url() != null;
    }

    private static boolean required() {
        return Boolean.parseBoolean(System.getenv("ENCODING_TEST_POSTGRES_REQUIRED"));
    }

    private static String url() {
        String value = System.getProperty("encoding.test.postgres.url");
        if (value == null || value.isBlank()) value = System.getenv("ENCODING_TEST_POSTGRES_URL");
        return value == null || value.isBlank() ? null : value;
    }

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        String url = url();
        if (url == null || !url.startsWith("jdbc:postgresql:")) {
            throw new IllegalStateException("A disposable PostgreSQL URL is required for encoding tests");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> fixtureSetting("USER"));
        registry.add("spring.datasource.password", () -> fixtureSetting("PASSWORD"));
    }

    private static String fixtureSetting(String name) {
        String value = System.getenv("ENCODING_TEST_POSTGRES_" + name);
        return value == null ? "fixture" : value;
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        for (UUID movie : movies) jdbc.update("delete from movies where id = ?", movie);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPLOADED", "QUEUED", "PROCESSING"})
    void inFlightViolationIsRecognizedByIndexName(String existingStatus) {
        UUID movie = movie();
        fixture(movie, UUID.randomUUID(), existingStatus, T0);

        Throwable failure = catchThrowable(() -> tx().executeWithoutResult(status -> {
            VideoAsset asset = queued(movie, "second");
            assets.saveAndFlush(asset);
        }));

        assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
        assertConstraint(failure, EncodingConstraints.VIDEO_ASSETS_MOVIE_IN_FLIGHT,
                EncodingConstraints.VIDEO_ASSETS_RAW_OBJECT);
        assertThat(jdbc.queryForObject("select count(*) from video_assets where movie_id = ?",
                Integer.class, movie)).isEqualTo(1);
    }

    @Test
    void rawObjectViolationIsDistinctFromInFlightAndLeavesTransactionAborted() {
        UUID firstMovie = movie();
        UUID secondMovie = movie();
        UUID first = UUID.randomUUID();
        fixture(firstMovie, first, "READY", T0);
        tx().executeWithoutResult(status -> {
            VideoAsset duplicate = VideoAsset.queued(locks.lockForMediaWrite(secondMovie),
                    UUID.randomUUID(), "raw-fixture", first.toString(), appClock);
            Throwable failure = catchThrowable(() -> assets.saveAndFlush(duplicate));
            assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
            assertConstraint(failure, EncodingConstraints.VIDEO_ASSETS_RAW_OBJECT,
                    EncodingConstraints.VIDEO_ASSETS_MOVIE_IN_FLIGHT);
            // 4.4 must roll back and perform ambiguous-commit readback in a fresh transaction.
            Throwable aborted = catchThrowable(() -> jdbc.queryForObject("select 1", Integer.class));
            assertThat(sqlState(aborted)).isEqualTo("25P02");
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("select count(*) from video_assets where movie_id = ?",
                Integer.class, secondMovie)).isZero();
    }

    @Test
    void failedRetryLoserUsesTheInFlightIndexName() {
        UUID movie = movie();
        UUID failed = UUID.randomUUID();
        fixture(movie, failed, "FAILED", T0);
        fixture(movie, UUID.randomUUID(), "QUEUED", T0.plusSeconds(1));

        Throwable failure = catchThrowable(() -> tx().executeWithoutResult(status ->
                entityManager.createNativeQuery("update video_assets set status = 'QUEUED' "
                        + "where id = :id and status = 'FAILED'")
                        .setParameter("id", failed).executeUpdate()));
        assertConstraint(failure, EncodingConstraints.VIDEO_ASSETS_MOVIE_IN_FLIGHT,
                EncodingConstraints.VIDEO_ASSETS_RAW_OBJECT);
        assertThat(jdbc.queryForObject("select status from video_assets where id = ?", String.class, failed))
                .isEqualTo("FAILED");
    }

    @Test
    void tiesUseUnsignedDatabaseOrderingForNewestCurrentAndStatus() {
        UUID movie = movie();
        UUID low = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
        UUID high = UUID.fromString("80000000-0000-0000-0000-000000000000");
        assertThat(high.compareTo(low)).isNegative();
        fixture(movie, low, "FAILED", T0);
        fixture(movie, high, "QUEUED", T0);

        assertThat(assets.findNewestByMovieId(movie)).get().extracting(VideoAsset::getId).isEqualTo(high);
        assertThat(lookup.videoStatus(movie)).isEqualTo("QUEUED");
        jdbc.update("update video_assets set status = 'READY' where movie_id = ?", movie);
        assertThat(assets.findCurrentReadyByMovieId(movie)).get().extracting(VideoAsset::getId).isEqualTo(high);
        assertThat(lookup.videoStatus(movie)).isEqualTo("READY");
    }

    @Test
    void setBasedAvailabilityCoversEmptyReadyReplacementAndNewestOnlySelection() {
        UUID empty = movie();
        UUID ready = movie();
        fixture(ready, UUID.randomUUID(), "READY", T0);
        List<UUID> requested = new ArrayList<>(List.of(empty, ready));
        for (String replacement : List.of("UPLOADED", "QUEUED", "PROCESSING", "FAILED")) {
            UUID movie = movie();
            requested.add(movie);
            fixture(movie, UUID.randomUUID(), "READY", T0);
            fixture(movie, UUID.randomUUID(), replacement, T0.plusSeconds(1));
        }
        UUID newestFailed = movie();
        UUID olderFailed = UUID.randomUUID();
        UUID newest = UUID.randomUUID();
        fixture(newestFailed, olderFailed, "FAILED", T0);
        fixture(newestFailed, newest, "FAILED", T0.plusSeconds(1));
        requested.add(newestFailed);

        Map<UUID, String> statuses = lookup.videoStatuses(requested);
        assertThat(statuses).doesNotContainKey(empty).containsEntry(newestFailed, "FAILED");
        for (UUID movie : requested.subList(1, requested.size() - 1)) {
            assertThat(statuses).containsEntry(movie, "READY");
        }
        assertThat(assets.findNewestByMovieId(newestFailed)).get()
                .extracting(VideoAsset::getId).isEqualTo(newest).isNotEqualTo(olderFailed);
        assertThat(assets.findCurrentReadyByMovieId(newestFailed)).isEmpty();
        assertThat(lookup.videoStatuses(List.of())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPLOADED", "QUEUED", "PROCESSING", "READY", "FAILED"})
    void aSingleAssetHasTheSameAvailabilityOnPostgres(String status) {
        UUID movie = movie();
        fixture(movie, UUID.randomUUID(), status, T0);
        assertThat(lookup.videoStatus(movie)).isEqualTo(status);
    }

    @Test
    void creationTimeIsSampledAfterObservedMovieLockWaitRatherThanTransactionStart() throws Exception {
        UUID movie = movie();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch tryLock = new CountDownLatch(1);
        AtomicInteger waiterPid = new AtomicInteger();
        AtomicReference<Instant> transactionStart = new AtomicReference<>();
        Future<VideoAsset> waiter = executor.submit(() -> tx().execute(status -> {
            transactionStart.set(jdbc.queryForObject("select now()", (rs, row) -> rs.getTimestamp(1).toInstant()));
            waiterPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
            started.countDown();
            await(tryLock);
            VideoAsset asset = queued(movie, "waiter");
            EncodingJob job = EncodingJob.firstAttempt(asset, UUID.randomUUID());
            assets.save(asset);
            jobs.saveAndFlush(job);
            assertThat(job.getQueuedAt()).isEqualTo(asset.getCreatedAt());
            assertThat(job.getPublishedAt()).isNull();
            return asset;
        }));
        try {
            await(started);
            Instant holderTime = tx().execute(status -> {
                LockedMovie lock = locks.lockForMediaWrite(movie);
                tryLock.countDown();
                waitForLock(waiterPid.get());
                // Terminal fixture represents the earlier completed asset, leaving room for the waiter.
                Instant created = appClock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
                assertThat(created).isAfter(transactionStart.get());
                assets.saveAndFlush(new VideoAsset(UUID.randomUUID(), lock.movieId(), "raw-fixture",
                        UUID.randomUUID().toString(), null, null, VideoAssetStatus.READY,
                        null, null, null, null, null, null, created, created));
                return created;
            });
            VideoAsset inserted = waiter.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(inserted.getCreatedAt()).isAfter(holderTime);
            assertThat(transactionStart.get()).isBefore(holderTime);
            assertThat(assets.findNewestByMovieId(movie)).get().extracting(VideoAsset::getId)
                    .isEqualTo(inserted.getId());
        } finally {
            tryLock.countDown();
        }
    }

    private void waitForLock(int pid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            jdbc.execute("select pg_stat_clear_snapshot()");
            if (Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from pg_stat_activity "
                    + "where pid = ? and wait_event_type = 'Lock')", Boolean.class, pid))) return;
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while observing the movie lock", exception);
            }
        }
        throw new AssertionError("The second transaction never waited for the movie lock");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating test transactions", exception);
        }
    }

    private UUID movie() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into movies (id,title,slug,description,status,created_at,updated_at) "
                        + "values (?,? ,?,?,'PUBLISHED',?,?)", id, "Encoding fixture", "encoding-" + id,
                "Disposable test fixture", Timestamp.from(T0), Timestamp.from(T0));
        movies.add(id);
        return id;
    }

    private void fixture(UUID movie, UUID id, String status, Instant created) {
        jdbc.update("insert into video_assets (id,movie_id,raw_bucket,raw_object_key,status,created_at,updated_at) "
                        + "values (?,?,'raw-fixture',?,?,?,?)", id, movie, id.toString(), status,
                Timestamp.from(created), Timestamp.from(created));
    }

    private VideoAsset queued(UUID movie, String key) {
        return VideoAsset.queued(locks.lockForMediaWrite(movie), UUID.randomUUID(),
                "raw-fixture", key + UUID.randomUUID(), appClock);
    }

    private TransactionTemplate tx() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setTimeout((int) TIMEOUT_SECONDS);
        return transaction;
    }

    private static void assertConstraint(Throwable failure, String expected, String other) {
        assertThat(failure).isNotNull();
        assertThat(EncodingConstraints.isViolationOf(failure, expected)).isTrue();
        assertThat(EncodingConstraints.isViolationOf(failure, other)).isFalse();
        assertThat(sqlState(failure)).isEqualTo("23505");
    }

    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) return sql.getSQLState();
        }
        return null;
    }
}
