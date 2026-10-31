package com.vodplatform.catalog.service;

import static com.vodplatform.catalog.persistence.CreditRole.ACTOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.tuple;

import com.vodplatform.catalog.config.CatalogConfiguration;
import com.vodplatform.catalog.dto.GenreResponse;
import com.vodplatform.catalog.dto.MovieCreditInput;
import com.vodplatform.catalog.dto.MovieDetail;
import com.vodplatform.catalog.dto.PersonResponse;
import com.vodplatform.catalog.dto.UpsertGenreRequest;
import com.vodplatform.catalog.dto.UpsertPersonRequest;
import com.vodplatform.catalog.exception.GenreConflictException;
import com.vodplatform.catalog.persistence.Genre;
import com.vodplatform.catalog.persistence.GenreRepository;
import com.vodplatform.catalog.persistence.Person;
import com.vodplatform.catalog.persistence.PersonRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two real transactions racing on the same genre or person slug, on H2 in PostgreSQL mode. A
 * holder transaction inserts the conflicting row and keeps it uncommitted; the service call under
 * test runs in a second transaction, and the holder ends only after H2 reports that second
 * transaction as blocked on the holder's row, so every test exercises the wait and its outcome.
 *
 * <p>Renames of the same genre or person race the same way on the row itself: the holder renames
 * it, and the rename under test waits for the row lock and then works on the committed row.
 *
 * <p>H2 reproduces the PostgreSQL behavior these writes rely on: an {@code ON CONFLICT DO NOTHING}
 * insert waits for the uncommitted conflicting row, then skips after a commit or inserts after a
 * rollback, and a READ COMMITTED read in the next statement sees the committed row. What H2 cannot
 * show is PostgreSQL aborting a transaction after a failed statement: on H2 a unique violation
 * leaves the transaction usable with or without the rename savepoint. That part, and the final
 * proof of these races, needs PostgreSQL itself.
 */
@DataJpaTest
@ContextConfiguration(classes = {
        CatalogConfiguration.class,
        MovieService.class,
        MovieAssignmentResolver.class,
        GenreService.class,
        PersonService.class,
        CatalogWriteRetry.class,
        CatalogConcurrencyTest.ClockConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CatalogConcurrencyTest {

    private static final long TIMEOUT_SECONDS = 20;
    private static final Duration BLOCKED_AFTER = Duration.ofMillis(200);

    @Autowired
    private MovieService movieService;

    @Autowired
    private GenreService genreService;

    @Autowired
    private PersonService personService;

    @Autowired
    private GenreRepository genreRepository;

    @Autowired
    private PersonRepository personRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private ExecutorService executor;

    @BeforeEach
    void start() {
        jdbc = new JdbcTemplate(dataSource);
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        for (String table : List.of("movie_credits", "movie_genres", "movies", "genres", "people")) {
            jdbc.update("delete from " + table);
        }
    }

    @Test
    void aMovieReusesTheGenreAnotherTransactionCreatedWhileItWaited() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdUncommitted(
                () -> genreRepository.insertIfAbsent(uuid(1), "Kinh Dị", "kinh-di"), release, true);

        Future<MovieDetail> racing = executor.submit(() -> movieService.create(
                new MovieRequestBuilder().slug("phim-2").genres(List.of("kinh di")).build()));
        awaitBlocked("insert into genres ");
        release.countDown();

        holder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        MovieDetail created = racing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(created.genres()).containsExactly("Kinh Dị");
        assertThat(genreRepository.findAll()).extracting(genre -> genre.getId()).containsExactly(uuid(1));
        assertThat(jdbc.queryForObject("select count(*) from movie_genres", Long.class)).isEqualTo(1);
    }

    @Test
    void anAdminCreateOfAGenreAnotherTransactionCommittedWhileItWaitedIsAConflict() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdUncommitted(
                () -> genreRepository.insertIfAbsent(uuid(1), "Kinh Dị", "kinh-di"), release, true);

        Future<GenreResponse> racing = executor.submit(() -> genreService.create(new UpsertGenreRequest("KINH DỊ")));
        awaitBlocked("insert into genres ");
        release.countDown();

        holder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Throwable failure = catchThrowable(() -> racing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertThat(failure).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(GenreConflictException.class);
        assertThat(genreRepository.count()).isEqualTo(1);
    }

    @Test
    void aGenreInsertProceedsWhenTheOtherTransactionRollsBack() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdUncommitted(
                () -> genreRepository.insertIfAbsent(uuid(1), "Kinh Dị", "kinh-di"), release, false);

        Future<GenreResponse> racing = executor.submit(() -> genreService.create(new UpsertGenreRequest("Kinh dị")));
        awaitBlocked("insert into genres ");
        release.countDown();

        holder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        GenreResponse created = racing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(created.name()).isEqualTo("Kinh dị");
        assertThat(genreRepository.findAll()).extracting(genre -> genre.getId()).containsExactly(created.id());
    }

    @Test
    void aPersonCreatedWhileAnotherTransactionTookItsSlugGetsTheNextSuffix() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdUncommitted(
                () -> personRepository.insertIfSlugFree(uuid(1), "Trấn Thành", "tran-thanh"), release, true);

        Future<PersonResponse> racing = executor.submit(() -> personService.create(new UpsertPersonRequest("Trấn Thành")));
        awaitBlocked("insert into people ");
        release.countDown();

        holder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(racing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).slug()).isEqualTo("tran-thanh-2");
        assertThat(personRepository.findAll()).extracting(Person::getSlug)
                .containsExactlyInAnyOrder("tran-thanh", "tran-thanh-2");
    }

    @Test
    void aNameOnlyCreditRacingWithTheSameNewPersonGetsTheNextSuffix() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdUncommitted(
                () -> personRepository.insertIfSlugFree(uuid(1), "Lý Hải", "ly-hai"), release, true);

        Future<MovieDetail> racing = executor.submit(() -> movieService.create(new MovieRequestBuilder()
                .people(List.of(new MovieCreditInput(null, "Lý Hải", ACTOR)))
                .build()));
        awaitBlocked("insert into people ");
        release.countDown();

        holder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        MovieDetail created = racing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        // The name lookup ran before the other person was committed, so this request created its
        // own person; names are not unique, and a later name-only "Lý Hải" asks for a personId.
        assertThat(created.people()).singleElement().satisfies(credit -> assertThat(credit.personId()).isNotEqualTo(uuid(1)));
        assertThat(personRepository.findAll()).extracting(Person::getName, Person::getSlug)
                .containsExactlyInAnyOrder(tuple("Lý Hải", "ly-hai"), tuple("Lý Hải", "ly-hai-2"));
    }

    @Test
    void aRenameRacingWithAnInsertOfItsNewSlugGetsTheNextSuffix() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> personRepository.insertIfSlugFree(uuid(2), "Bob", "bob"));
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdUncommitted(
                () -> personRepository.insertIfSlugFree(uuid(1), "Trấn Thành", "tran-thanh"), release, true);

        Future<PersonResponse> racing = executor.submit(
                () -> personService.rename(uuid(2), new UpsertPersonRequest("Trấn Thành")));
        awaitBlocked("update people ");
        release.countDown();

        holder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(racing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .isEqualTo(new PersonResponse(uuid(2), "Trấn Thành", "tran-thanh-2"));
        assertThat(personRepository.findAll()).extracting(Person::getId, Person::getSlug)
                .containsExactlyInAnyOrder(tuple(uuid(1), "tran-thanh"), tuple(uuid(2), "tran-thanh-2"));
    }

    // ---- renames that wait for the row lock ----

    /**
     * Another transaction renames person "Lý Hải" (slug {@code ly-hai}) to "Trấn Thành" and creates
     * a new "Lý Hải", which reclaims {@code ly-hai}, and keeps both uncommitted. A rename of the
     * first person to "LÝ HẢI" waits for the person's row lock and then decides on the committed
     * row: the slug base changes from {@code tran-thanh} to {@code ly-hai}, which the new person
     * has, so the rename takes {@code ly-hai-2}. Read without the lock, the person still looked
     * like "Lý Hải": the rename kept {@code ly-hai} for the unchanged base and failed on
     * {@code ux_people_slug} once the other transaction committed.
     */
    @Test
    void aPersonRenameWaitingForAnotherRenameOfThePersonDecidesOnTheCommittedRow() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> personRepository.insertIfSlugFree(uuid(1), "Lý Hải", "ly-hai"));
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdUncommitted(() -> {
            personService.rename(uuid(1), new UpsertPersonRequest("Trấn Thành"));
            personService.create(new UpsertPersonRequest("Lý Hải"));
        }, release, true);

        Future<PersonResponse> racing = executor.submit(
                () -> personService.rename(uuid(1), new UpsertPersonRequest("LÝ HẢI")));
        awaitLockedReadBlocked("people");
        release.countDown();

        holder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(racing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .isEqualTo(new PersonResponse(uuid(1), "LÝ HẢI", "ly-hai-2"));
        assertThat(personRepository.findAll()).extracting(Person::getName, Person::getSlug)
                .containsExactlyInAnyOrder(tuple("LÝ HẢI", "ly-hai-2"), tuple("Lý Hải", "ly-hai"));
    }

    /**
     * Another transaction renames genre "Hài" to "Hài Hước" and keeps it uncommitted. A rename of
     * the genre back to "Hài" waits for the genre's row lock and then renames the committed row.
     * Read without the lock, the genre still looked like "Hài": the rename found nothing to change,
     * reported "Hài", and the genre kept the other transaction's name.
     */
    @Test
    void aGenreRenameWaitingForAnotherRenameOfTheGenreAppliesToTheCommittedRow() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> genreRepository.insertIfAbsent(uuid(1), "Hài", "hai"));
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holdUncommitted(
                () -> genreService.rename(uuid(1), new UpsertGenreRequest("Hài Hước")), release, true);

        Future<GenreResponse> racing = executor.submit(
                () -> genreService.rename(uuid(1), new UpsertGenreRequest("Hài")));
        awaitLockedReadBlocked("genres");
        release.countDown();

        holder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(racing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(new GenreResponse(uuid(1), "Hài", "hai"));
        assertThat(genreRepository.findAll()).extracting(Genre::getId, Genre::getName, Genre::getSlug)
                .containsExactly(tuple(uuid(1), "Hài", "hai"));
    }

    // ---- helpers ----

    /**
     * Runs the write in a transaction on another thread and returns once it is written; the
     * transaction stays open until {@code release} opens, then commits or rolls back.
     */
    private Future<?> holdUncommitted(Runnable write, CountDownLatch release, boolean commit) throws InterruptedException {
        CountDownLatch written = new CountDownLatch(1);
        Future<?> holder = executor.submit((Callable<Void>) () -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                write.run();
                written.countDown();
                awaitUninterruptibly(release);
                if (!commit) {
                    status.setRollbackOnly();
                }
            });
            return null;
        });
        if (!written.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            fail("The holder transaction did not write its row");
        }
        return holder;
    }

    /**
     * Waits until the racing transaction is blocked on the holder's row: H2 does not report row
     * lock waits as a blocker, but shows the waiting statement as still executing, which an
     * uncontended single-row write on an in-memory database never is for long.
     */
    private void awaitBlocked(String statementPrefix) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            OffsetDateTime blockedSince = OffsetDateTime.now().minus(BLOCKED_AFTER);
            boolean blocked = jdbc.query(
                    "select executing_statement, executing_statement_start from information_schema.sessions"
                            + " where session_state = 'RUNNING' and executing_statement_start is not null",
                    (row, index) -> row.getString(1).toLowerCase(Locale.ROOT).startsWith(statementPrefix)
                            && row.getObject(2, OffsetDateTime.class).isBefore(blockedSince)
            ).contains(true);
            if (blocked) {
                return;
            }
            Thread.sleep(10);
        }
        fail("The racing transaction never waited for the holder transaction");
    }

    /**
     * Waits until the racing transaction's locking read of a row in the table waits for the
     * holder, which has written that row. H2 reports a wait for a written row, unlike the wait for
     * an uncommitted unique key ({@link #awaitBlocked}), as a {@code BLOCKED} session with a
     * blocker.
     */
    private void awaitLockedReadBlocked(String table) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            boolean waiting = jdbc.queryForList(
                    "select executing_statement from information_schema.sessions"
                            + " where session_state = 'BLOCKED' and blocker_id is not null",
                    String.class
            ).stream().map(sql -> sql.toLowerCase(Locale.ROOT)).anyMatch(sql -> sql.startsWith("select ")
                    && sql.contains(" from " + table + " ") && sql.contains(" for update"));
            if (waiting) {
                return;
            }
            Thread.sleep(10);
        }
        fail("The racing transaction never waited for the " + table + " row the holder transaction wrote");
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The holder transaction was never released");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static UUID uuid(int value) {
        return new UUID(0L, value);
    }

    @Configuration(proxyBeanMethods = false)
    static class ClockConfiguration {

        @Bean
        MutableClock clock() {
            return new MutableClock(Instant.parse("2026-10-01T08:00:00Z"));
        }

        @Bean
        VideoStatusLookup videoStatusLookup() {
            return movieIds -> java.util.Map.of();
        }
    }
}
