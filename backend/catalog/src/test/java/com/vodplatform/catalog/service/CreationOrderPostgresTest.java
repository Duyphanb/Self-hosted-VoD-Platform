package com.vodplatform.catalog.service;

import static com.vodplatform.catalog.persistence.CreditRole.ACTOR;
import static com.vodplatform.catalog.persistence.CreditRole.DIRECTOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.vodplatform.catalog.config.CatalogConfiguration;
import com.vodplatform.catalog.dto.CreditedPerson;
import com.vodplatform.catalog.dto.MovieCreditInput;
import com.vodplatform.catalog.dto.MovieDetail;
import com.vodplatform.catalog.dto.UpsertMovieRequest;
import com.vodplatform.catalog.persistence.CreditRole;
import com.vodplatform.catalog.persistence.GenreRepository;
import com.vodplatform.catalog.persistence.MovieRepository;
import com.vodplatform.catalog.persistence.PersonRepository;
import com.vodplatform.catalog.text.SlugNormalizer;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Opt-in PostgreSQL check that two movie writes creating the same new genres, or the same new
 * people, in opposite request order both succeed. Each write is held after its first insert until
 * the other write has inserted too or waits for a lock. With inserts in request order, that
 * interleaving made each transaction wait for the other's uncommitted row, and PostgreSQL aborted
 * one of them with a deadlock ({@code SQLSTATE 40P01}); with inserts in slug order, the second
 * write waits at its first insert and both finish.
 *
 * <p>Skipped unless the system property {@value #URL_PROPERTY} or the environment variable
 * {@value #URL_VARIABLE} holds the JDBC URL, credentials included, of a PostgreSQL database
 * migrated with the Flyway scripts, for example
 * {@code jdbc:postgresql://127.0.0.1:55432/vod_platform?user=...&password=...}. Every row the tests
 * write carries a random token in its slug and is deleted afterwards.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@ContextConfiguration(classes = CatalogConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIf("postgresConfigured")
class CreationOrderPostgresTest {

    static final String URL_PROPERTY = "catalog.test.postgres.url";
    static final String URL_VARIABLE = "CATALOG_TEST_POSTGRES_URL";

    private static final long TIMEOUT_SECONDS = 30;

    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private GenreRepository genreRepository;

    @Autowired
    private PersonRepository personRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    private final String token = randomLetters();
    private JdbcTemplate jdbc;
    private ExecutorService executor;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CreationOrderPostgresTest::postgresUrl);
    }

    static boolean postgresConfigured() {
        String url = postgresUrl();
        return url != null && url.startsWith("jdbc:postgresql:");
    }

    private static String postgresUrl() {
        String url = System.getProperty(URL_PROPERTY);
        return url == null || url.isBlank() ? System.getenv(URL_VARIABLE) : url;
    }

    @BeforeEach
    void start() {
        jdbc = new JdbcTemplate(dataSource);
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        String pattern = slugPattern();
        jdbc.update("delete from movie_credits where movie_id in (select id from movies where slug like ?)", pattern);
        jdbc.update("delete from movie_genres where movie_id in (select id from movies where slug like ?)", pattern);
        jdbc.update("delete from movies where slug like ?", pattern);
        jdbc.update("delete from people where slug like ?", pattern);
        jdbc.update("delete from genres where slug like ?", pattern);
    }

    @Test
    void twoMoviesCreatingTheSameNewGenresInOppositeOrderBothSucceed() throws Exception {
        String first = "Thể Loại A " + token;
        String second = "Thể Loại B " + token;
        Rendezvous rendezvous = new Rendezvous();

        Future<MovieDetail> forward = create(holdingAfterFirstGenreInsert(rendezvous),
                new MovieRequestBuilder().slug("phim-a-" + token).genres(List.of(first, second)).build());
        Future<MovieDetail> backward = create(holdingAfterFirstGenreInsert(rendezvous),
                new MovieRequestBuilder().slug("phim-b-" + token).genres(List.of(second, first)).build());
        List<MovieDetail> created = results(forward, backward);

        rendezvous.assertOverlapped();
        assertThat(created).allSatisfy(movie -> assertThat(movie.genres()).containsExactly(first, second));
        assertThat(jdbc.queryForList("select slug from genres where slug like ?", String.class, slugPattern()))
                .containsExactlyInAnyOrder(SlugNormalizer.slugify(first), SlugNormalizer.slugify(second));
        assertThat(jdbc.queryForObject(
                "select count(*) from movie_genres where movie_id in (select id from movies where slug like ?)",
                Long.class, slugPattern())).isEqualTo(4);
    }

    @Test
    void twoMoviesCreatingTheSameNewPeopleInOppositeOrderBothSucceed() throws Exception {
        // An existing genre, so the only inserts after the movie rows are the people.
        String genre = "Thể Loại " + token;
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> genreRepository.insertIfAbsent(UUID.randomUUID(), genre, SlugNormalizer.slugify(genre)));
        String alice = "Alice " + token;
        String bob = "Bob " + token;
        Rendezvous rendezvous = new Rendezvous();

        Future<MovieDetail> forward = create(holdingAfterFirstPersonInsert(rendezvous), new MovieRequestBuilder()
                .slug("phim-a-" + token)
                .genres(List.of(genre))
                .people(List.of(byName(alice, ACTOR), byName(bob, DIRECTOR)))
                .build());
        Future<MovieDetail> backward = create(holdingAfterFirstPersonInsert(rendezvous), new MovieRequestBuilder()
                .slug("phim-b-" + token)
                .genres(List.of(genre))
                .people(List.of(byName(bob, ACTOR), byName(alice, DIRECTOR)))
                .build());
        List<MovieDetail> created = results(forward, backward);

        rendezvous.assertOverlapped();
        // Credits keep the request order, whatever order the people were inserted in.
        assertThat(created.get(0).people()).extracting(CreditedPerson::name, CreditedPerson::role)
                .containsExactly(tuple(alice, ACTOR), tuple(bob, DIRECTOR));
        assertThat(created.get(1).people()).extracting(CreditedPerson::name, CreditedPerson::role)
                .containsExactly(tuple(bob, ACTOR), tuple(alice, DIRECTOR));
        assertThat(jdbc.query(
                "select p.name, c.credit_role, c.sort_order from movie_credits c join people p on p.id = c.person_id"
                        + " join movies m on m.id = c.movie_id where m.slug = ? order by c.sort_order",
                (row, index) -> tuple(row.getString(1), CreditRole.valueOf(row.getString(2)), row.getInt(3)),
                "phim-b-" + token
        )).containsExactly(tuple(bob, ACTOR, 0), tuple(alice, DIRECTOR, 1));
        // Both name lookups ran before either write committed, so each write created its own two
        // people (names are not unique), and the later one took the next slug suffix.
        String aliceSlug = SlugNormalizer.personSlugBase(alice);
        String bobSlug = SlugNormalizer.personSlugBase(bob);
        assertThat(jdbc.queryForList("select slug from people where slug like ?", String.class, slugPattern()))
                .containsExactlyInAnyOrder(aliceSlug, aliceSlug + "-2", bobSlug, bobSlug + "-2");
    }

    // ---- helpers ----

    /**
     * A movie service for one transaction whose genre inserts run as usual, then hold that
     * transaction after its first insert ({@link Rendezvous}).
     */
    private MovieService holdingAfterFirstGenreInsert(Rendezvous rendezvous) {
        Runnable hold = rendezvous.party();
        GenreRepository genres = mock(GenreRepository.class, AdditionalAnswers.delegatesTo(genreRepository));
        doAnswer(invocation -> {
            int inserted = genreRepository.insertIfAbsent(
                    invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            hold.run();
            return inserted;
        }).when(genres).insertIfAbsent(any(), any(), any());
        return new MovieService(movieRepository, new MovieAssignmentResolver(genres, personRepository), Clock.systemUTC());
    }

    /**
     * A movie service for one transaction whose person inserts run as usual, then hold that
     * transaction after its first insert ({@link Rendezvous}).
     */
    private MovieService holdingAfterFirstPersonInsert(Rendezvous rendezvous) {
        Runnable hold = rendezvous.party();
        PersonRepository people = mock(PersonRepository.class, AdditionalAnswers.delegatesTo(personRepository));
        doAnswer(invocation -> {
            int inserted = personRepository.insertIfSlugFree(
                    invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            hold.run();
            return inserted;
        }).when(people).insertIfSlugFree(any(), any(), any());
        return new MovieService(movieRepository, new MovieAssignmentResolver(genreRepository, people), Clock.systemUTC());
    }

    /**
     * Runs the create in its own transaction on another thread; the service is not a Spring
     * proxy, so the transaction comes from the template.
     */
    private Future<MovieDetail> create(MovieService service, UpsertMovieRequest request) {
        return executor.submit(
                () -> new TransactionTemplate(transactionManager).execute(status -> service.create(request)));
    }

    /**
     * Waits for both writes and returns their results in order, or fails with the SQLSTATE of
     * every write that failed.
     */
    private static List<MovieDetail> results(Future<MovieDetail> first, Future<MovieDetail> second) throws Exception {
        List<MovieDetail> results = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        List<Future<MovieDetail>> writes = List.of(first, second);
        for (int index = 0; index < writes.size(); index++) {
            try {
                results.add(writes.get(index).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            } catch (ExecutionException exception) {
                failures.add(exception.getCause());
                messages.add("write " + (index + 1) + " failed with SQLSTATE " + sqlState(exception.getCause())
                        + ": " + exception.getCause());
            }
        }
        if (!failures.isEmpty()) {
            AssertionError failed = new AssertionError(String.join("; ", messages));
            failures.forEach(failed::addSuppressed);
            throw failed;
        }
        return results;
    }

    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
        }
        return "none";
    }

    private int lockWaiters() {
        Integer waiters = jdbc.queryForObject(
                "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'",
                Integer.class);
        return waiters == null ? 0 : waiters;
    }

    private String slugPattern() {
        return "%" + token + "%";
    }

    private static MovieCreditInput byName(String name, CreditRole role) {
        return new MovieCreditInput(null, name, role);
    }

    /**
     * Letters only, so the slugs built from it never end in a number that looks like a suffix.
     */
    private static String randomLetters() {
        StringBuilder letters = new StringBuilder();
        for (int index = 0; index < 12; index++) {
            letters.append((char) ('a' + ThreadLocalRandom.current().nextInt(26)));
        }
        return letters.toString();
    }

    /**
     * Holds each write after its first insert until the other write has inserted too, or is
     * waiting for a lock, so that both transactions are open with a row written when they go on.
     */
    private final class Rendezvous {

        private final CountDownLatch inserted = new CountDownLatch(2);
        private final AtomicBoolean timedOut = new AtomicBoolean();

        /**
         * The hook of one write: holds it after its first insert only.
         */
        Runnable party() {
            AtomicBoolean first = new AtomicBoolean(true);
            return () -> {
                if (first.getAndSet(false)) {
                    hold();
                }
            };
        }

        void assertOverlapped() {
            assertThat(inserted.getCount()).as("writes that never inserted").isZero();
            assertThat(timedOut).as("a write held in vain").isFalse();
        }

        private void hold() {
            inserted.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            while (System.nanoTime() < deadline) {
                if (inserted.getCount() == 0 || lockWaiters() > 0) {
                    return;
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            timedOut.set(true);
        }
    }
}
