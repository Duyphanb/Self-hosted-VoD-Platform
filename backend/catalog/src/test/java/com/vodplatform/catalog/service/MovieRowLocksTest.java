package com.vodplatform.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.fail;

import com.vodplatform.catalog.config.CatalogConfiguration;
import com.vodplatform.catalog.exception.MovieNotFoundException;
import com.vodplatform.catalog.persistence.Movie;
import com.vodplatform.catalog.persistence.MovieRepository;
import com.vodplatform.catalog.persistence.MovieStatus;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** The public lock boundary joins its caller's transaction and serializes competing media writes. */
@DataJpaTest
@ContextConfiguration(classes = {CatalogConfiguration.class, MovieRowLocks.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MovieRowLocksTest {

    private static final long TIMEOUT_SECONDS = 20;
    private final UUID movieId = UUID.randomUUID();

    @Autowired
    private MovieRowLocks locks;

    @Autowired
    private MovieRepository movies;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void cleanUp() {
        movies.deleteById(movieId);
    }

    @Test
    void requiresAnExistingTransaction() {
        assertThatExceptionOfType(IllegalTransactionStateException.class)
                .isThrownBy(() -> locks.lockForMediaWrite(movieId));
    }

    @Test
    void missingMovieUsesTheCatalogNotFoundOutcome() {
        assertThatExceptionOfType(MovieNotFoundException.class)
                .isThrownBy(() -> transaction().execute(status -> locks.lockForMediaWrite(movieId)));
    }

    @ParameterizedTest
    @EnumSource(MovieStatus.class)
    void locksMoviesInEveryStatus(MovieStatus status) {
        insertMovie(status);

        LockedMovie locked = transaction().execute(tx -> locks.lockForMediaWrite(movieId));

        assertThat(locked.movieId()).isEqualTo(movieId);
        assertThat(locked.status()).isEqualTo(status);
    }

    @Test
    void anotherTransactionWaitsAndReadsThePreviousWritersCommittedState() throws Exception {
        insertMovie(MovieStatus.DRAFT);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        CountDownLatch holderReady = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = executor.submit(() -> transaction().executeWithoutResult(tx -> {
                locks.lockForMediaWrite(movieId);
                jdbc.update("update movies set status = 'PUBLISHED' where id = ?", movieId);
                holderReady.countDown();
                await(releaseHolder);
            }));
            assertThat(holderReady.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            Future<LockedMovie> contender = executor.submit(
                    () -> transaction().execute(tx -> locks.lockForMediaWrite(movieId)));

            awaitBlocked(jdbc);
            assertThat(contender.isDone()).isFalse();
            releaseHolder.countDown();
            holder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(contender.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).status()).isEqualTo(MovieStatus.PUBLISHED);
        } finally {
            releaseHolder.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void insertMovie(MovieStatus status) {
        transaction().executeWithoutResult(tx -> movies.saveAndFlush(new Movie(
                movieId, "Phim", "media-lock-" + movieId, "Mô tả", null, null, status, Instant.EPOCH)));
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The holder transaction was never released");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static void awaitBlocked(JdbcTemplate jdbc) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            boolean blocked = jdbc.queryForList(
                    "select executing_statement from information_schema.sessions"
                            + " where session_state = 'BLOCKED' and blocker_id is not null", String.class)
                    .stream().map(sql -> sql.toLowerCase(Locale.ROOT))
                    .anyMatch(sql -> sql.contains(" from movies ") && sql.contains(" for update"));
            if (blocked) {
                return;
            }
            Thread.sleep(10);
        }
        fail("The competing media transaction never waited for the movie row lock");
    }
}
