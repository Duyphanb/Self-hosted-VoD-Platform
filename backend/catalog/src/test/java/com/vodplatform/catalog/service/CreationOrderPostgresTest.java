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
import com.vodplatform.catalog.dto.GenreResponse;
import com.vodplatform.catalog.dto.MovieCreditInput;
import com.vodplatform.catalog.dto.MovieDetail;
import com.vodplatform.catalog.dto.PersonResponse;
import com.vodplatform.catalog.dto.UpsertGenreRequest;
import com.vodplatform.catalog.dto.UpsertMovieRequest;
import com.vodplatform.catalog.dto.UpsertPersonRequest;
import com.vodplatform.catalog.exception.GenreConflictException;
import com.vodplatform.catalog.persistence.CreditRole;
import com.vodplatform.catalog.persistence.GenreRepository;
import com.vodplatform.catalog.persistence.MovieRepository;
import com.vodplatform.catalog.persistence.PersonRepository;
import com.vodplatform.catalog.text.SlugNormalizer;
import com.vodplatform.shared.error.ApiException;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Opt-in PostgreSQL checks of concurrent catalog writes that hold row locks the other one needs.
 *
 * <p>Two movie writes creating the same new genres, or the same new people, in opposite request
 * order both succeed without any retry. Each write is held after its first insert until the other
 * write has inserted too or waits for a lock. With inserts in request order, that interleaving made
 * each transaction wait for the other's uncommitted row, and PostgreSQL aborted one of them with a
 * deadlock ({@code SQLSTATE 40P01}); with inserts in slug order, the second write waits at its
 * first insert and both finish. These writes run in a transaction the test starts, so
 * {@link CatalogWriteRetry} gives them a single attempt and a deadlock would fail the test.
 *
 * <p>The slug order cannot prevent every deadlock, and {@link CatalogWriteRetry} handles the rest:
 * two movie writes whose new people take crossed slug suffixes, and a movie write racing with an
 * admin rename of a person or genre it references. Those tests run the services as the API does,
 * with the retry, and check that exactly one attempt was aborted and run again, and that both
 * writes then finish.
 *
 * <p>A rename of a person or genre that another transaction is renaming waits for the row lock and
 * then works on the row that transaction committed, in a single attempt. The other transaction is
 * held uncommitted until the rename waits for a lock.
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

    // ---- the slug order, without the retry ----

    @Test
    void twoMoviesCreatingTheSameNewGenresInOppositeOrderBothSucceed() throws Exception {
        String first = "Thể Loại A " + token;
        String second = "Thể Loại B " + token;
        Rendezvous rendezvous = new Rendezvous();

        Future<MovieDetail> forward = createInOneAttempt(
                movieService(holdingGenres(rendezvous.party()), personRepository, writeRetry()),
                new MovieRequestBuilder().slug("phim-a-" + token).genres(List.of(first, second)).build());
        Future<MovieDetail> backward = createInOneAttempt(
                movieService(holdingGenres(rendezvous.party()), personRepository, writeRetry()),
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
        String genre = existingGenre();
        String alice = "Alice " + token;
        String bob = "Bob " + token;
        Rendezvous rendezvous = new Rendezvous();

        Future<MovieDetail> forward = createInOneAttempt(
                movieService(genreRepository, holdingPeople(rendezvous.party()), writeRetry()),
                new MovieRequestBuilder()
                        .slug("phim-a-" + token)
                        .genres(List.of(genre))
                        .people(List.of(byName(alice, ACTOR), byName(bob, DIRECTOR)))
                        .build());
        Future<MovieDetail> backward = createInOneAttempt(
                movieService(genreRepository, holdingPeople(rendezvous.party()), writeRetry()),
                new MovieRequestBuilder()
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

    // ---- deadlocks the retry handles ----

    /**
     * Person "X" exists. Write A creates "X!" (base {@code x}, so it takes {@code x-2}) and "X 1"
     * (base {@code x-1}); write B creates "X 1" and "X 2" (bases {@code x-1} and {@code x-2}). In
     * slug-base order, A inserts {@code x-2} then {@code x-1}, and B inserts {@code x-1} then
     * {@code x-2}: each then waits for the other's first row.
     */
    @Test
    void twoMoviesWhosePeopleTakeCrossedSlugSuffixesBothSucceedAfterOneRetry() throws Exception {
        String genre = existingGenre();
        String seed = "X " + token;
        inTransaction(() -> personRepository.insertIfSlugFree(
                UUID.randomUUID(), seed, SlugNormalizer.personSlugBase(seed)));
        Rendezvous rendezvous = new Rendezvous();
        CountingTransactionManager firstTransactions = new CountingTransactionManager(transactionManager);
        CountingTransactionManager secondTransactions = new CountingTransactionManager(transactionManager);
        MovieService first = movieService(genreRepository, holdingPeople(rendezvous.party()),
                new CatalogWriteRetry(firstTransactions));
        MovieService second = movieService(genreRepository, holdingPeople(rendezvous.party()),
                new CatalogWriteRetry(secondTransactions));

        Future<MovieDetail> crossedFirst = submit(() -> first.create(new MovieRequestBuilder()
                .slug("phim-a-" + token)
                .genres(List.of(genre))
                .people(List.of(byName(seed + "!", ACTOR), byName(seed + " 1", DIRECTOR)))
                .build()));
        Future<MovieDetail> crossedSecond = submit(() -> second.create(new MovieRequestBuilder()
                .slug("phim-b-" + token)
                .genres(List.of(genre))
                .people(List.of(byName(seed + " 1", ACTOR), byName(seed + " 2", DIRECTOR)))
                .build()));
        List<MovieDetail> created = results(crossedFirst, crossedSecond);

        rendezvous.assertOverlapped();
        assertThat(firstTransactions.begun.get() + secondTransactions.begun.get())
                .as("attempts, one of them aborted as a deadlock victim and run again").isEqualTo(3);
        assertThat(created.get(0).people()).extracting(CreditedPerson::name, CreditedPerson::role)
                .containsExactly(tuple(seed + "!", ACTOR), tuple(seed + " 1", DIRECTOR));
        assertThat(created.get(1).people()).extracting(CreditedPerson::name, CreditedPerson::role)
                .containsExactly(tuple(seed + " 1", ACTOR), tuple(seed + " 2", DIRECTOR));
    }

    /**
     * The movie write creates person "X" and holds; the rename of person P to "X" then updates P,
     * which locks it, and waits for the movie write's uncommitted slug. The movie write goes on to
     * credit P, which needs a key-share lock on P: each waits for the other.
     */
    @Test
    void aPersonRenameRacingWithAMovieThatCreditsThePersonBothSucceedAfterOneRetry() throws Exception {
        String genre = existingGenre();
        UUID personId = UUID.randomUUID();
        String oldName = "Cũ " + token;
        inTransaction(() -> personRepository.insertIfSlugFree(
                personId, oldName, SlugNormalizer.personSlugBase(oldName)));
        String newName = "X " + token;
        Handoff handoff = new Handoff();
        CountingTransactionManager movieTransactions = new CountingTransactionManager(transactionManager);
        CountingTransactionManager renameTransactions = new CountingTransactionManager(transactionManager);
        MovieService movies = movieService(genreRepository, holdingPeople(handoff::holdUntilBlocked),
                new CatalogWriteRetry(movieTransactions));
        PersonService people = new PersonService(personRepository, new CatalogWriteRetry(renameTransactions));

        Future<MovieDetail> movie = submit(() -> movies.create(new MovieRequestBuilder()
                .slug("phim-a-" + token)
                .genres(List.of(genre))
                .people(List.of(byName(newName, ACTOR), new MovieCreditInput(personId, null, DIRECTOR)))
                .build()));
        Future<PersonResponse> rename = submit(() -> {
            handoff.awaitInserted();
            return people.rename(personId, new UpsertPersonRequest(newName));
        });
        List<Object> outcomes = outcomes(movie, rename);

        handoff.assertBlocked();
        assertThat(movieTransactions.begun.get() + renameTransactions.begun.get())
                .as("attempts, one of them aborted as a deadlock victim and run again").isEqualTo(3);
        assertThat(outcomes.get(0)).isInstanceOfSatisfying(MovieDetail.class, created -> {
            assertThat(created.people()).extracting(CreditedPerson::role).containsExactly(ACTOR, DIRECTOR);
            assertThat(created.people().get(1).personId()).isEqualTo(personId);
        });
        assertThat(outcomes.get(1)).isInstanceOfSatisfying(PersonResponse.class, renamed -> {
            assertThat(renamed.id()).isEqualTo(personId);
            assertThat(renamed.name()).isEqualTo(newName);
        });
    }

    /**
     * The movie write creates genre "Mới" and holds; the rename of genre G to "Mới" then updates
     * G, which locks it, and waits for the movie write's uncommitted name and slug. The movie write
     * goes on to assign G, which needs a key-share lock on G: each waits for the other. When the
     * rename is the aborted one, its retry finds the genre the movie write created: a 409.
     */
    @Test
    void aGenreRenameRacingWithAMovieThatAssignsTheGenreFinishesAfterOneRetry() throws Exception {
        UUID genreId = UUID.randomUUID();
        String oldName = "Cũ " + token;
        inTransaction(() -> genreRepository.insertIfAbsent(genreId, oldName, SlugNormalizer.slugify(oldName)));
        String newName = "Mới " + token;
        Handoff handoff = new Handoff();
        CountingTransactionManager movieTransactions = new CountingTransactionManager(transactionManager);
        CountingTransactionManager renameTransactions = new CountingTransactionManager(transactionManager);
        MovieService movies = movieService(holdingGenres(handoff::holdUntilBlocked), personRepository,
                new CatalogWriteRetry(movieTransactions));
        GenreService genres = new GenreService(genreRepository, new CatalogWriteRetry(renameTransactions));

        Future<MovieDetail> movie = submit(() -> movies.create(new MovieRequestBuilder()
                .slug("phim-a-" + token)
                .genres(List.of(oldName, newName))
                .build()));
        Future<GenreResponse> rename = submit(() -> {
            handoff.awaitInserted();
            return genres.rename(genreId, new UpsertGenreRequest(newName));
        });
        List<Object> outcomes = outcomes(movie, rename);

        handoff.assertBlocked();
        assertThat(movieTransactions.begun.get() + renameTransactions.begun.get())
                .as("attempts, one of them aborted as a deadlock victim and run again").isEqualTo(3);
        assertThat(outcomes.get(0)).isInstanceOf(MovieDetail.class);
        assertThat(outcomes.get(1)).satisfiesAnyOf(
                outcome -> assertThat(outcome).isInstanceOf(GenreConflictException.class),
                outcome -> assertThat(outcome).isEqualTo(
                        new GenreResponse(genreId, newName, SlugNormalizer.slugify(newName))));
    }

    // ---- renames that wait for the row lock ----

    /**
     * Person P "Foo" has the slug {@code foo}. Another transaction renames P to "Bar", creates
     * person Q "Foo", which reclaims {@code foo}, and holds both uncommitted. The rename of P to
     * "FOO" waits for P's row lock and then reads P as "Bar": the slug base changes, {@code foo}
     * belongs to Q, and P takes {@code foo-2}. Read without the lock, P still looked like "Foo":
     * the rename kept {@code foo} for the unchanged base, failed on {@code ux_people_slug} once the
     * other transaction committed, and ended with a 500.
     */
    @Test
    void aPersonRenameWaitingForAnotherRenameOfThePersonDecidesOnTheCommittedRow() throws Exception {
        UUID personId = UUID.randomUUID();
        String oldName = "Foo " + token;
        String oldSlug = SlugNormalizer.personSlugBase(oldName);
        inTransaction(() -> personRepository.insertIfSlugFree(personId, oldName, oldSlug));
        String newName = oldName.toUpperCase(Locale.ROOT);
        PersonService other = new PersonService(personRepository, writeRetry());
        CountingTransactionManager renameTransactions = new CountingTransactionManager(transactionManager);
        PersonService people = new PersonService(personRepository, new CatalogWriteRetry(renameTransactions));
        AtomicReference<PersonResponse> reclaimed = new AtomicReference<>();
        CountDownLatch release = new CountDownLatch(1);

        Future<?> holder = holdUncommitted(() -> {
            other.rename(personId, new UpsertPersonRequest("Bar " + token));
            reclaimed.set(other.create(new UpsertPersonRequest(oldName)));
        }, release);
        Future<PersonResponse> rename = submit(() -> people.rename(personId, new UpsertPersonRequest(newName)));
        boolean waited = awaitCondition(() -> lockWaiters() > 0);
        release.countDown();
        List<Object> outcomes = outcomes(rename, holder);

        assertThat(waited).as("the rename waited for the row lock").isTrue();
        assertThat(outcomes.get(0)).isEqualTo(new PersonResponse(personId, newName, oldSlug + "-2"));
        assertThat(renameTransactions.begun.get()).as("rename attempts").isEqualTo(1);
        assertThat(reclaimed.get().slug()).isEqualTo(oldSlug);
        assertThat(jdbc.query("select id, name, slug from people where slug like ?",
                (row, index) -> tuple(row.getObject(1, UUID.class), row.getString(2), row.getString(3)),
                slugPattern()
        )).containsExactlyInAnyOrder(
                tuple(personId, newName, oldSlug + "-2"),
                tuple(reclaimed.get().id(), oldName, oldSlug));
    }

    /**
     * Another transaction renames genre G "Cũ" to "Đổi" and holds it uncommitted. The rename of G
     * back to "Cũ" waits for G's row lock and then renames the committed row. Read without the
     * lock, G still looked like "Cũ": the rename found nothing to change, did not wait, reported
     * "Cũ", and G kept the other transaction's name.
     */
    @Test
    void aGenreRenameWaitingForAnotherRenameOfTheGenreAppliesToTheCommittedRow() throws Exception {
        UUID genreId = UUID.randomUUID();
        String oldName = "Cũ " + token;
        String oldSlug = SlugNormalizer.slugify(oldName);
        inTransaction(() -> genreRepository.insertIfAbsent(genreId, oldName, oldSlug));
        GenreService other = new GenreService(genreRepository, writeRetry());
        CountingTransactionManager renameTransactions = new CountingTransactionManager(transactionManager);
        GenreService genres = new GenreService(genreRepository, new CatalogWriteRetry(renameTransactions));
        CountDownLatch release = new CountDownLatch(1);

        Future<?> holder = holdUncommitted(
                () -> other.rename(genreId, new UpsertGenreRequest("Đổi " + token)), release);
        Future<GenreResponse> rename = submit(() -> genres.rename(genreId, new UpsertGenreRequest(oldName)));
        boolean waited = awaitCondition(() -> lockWaiters() > 0);
        release.countDown();
        List<Object> outcomes = outcomes(rename, holder);

        assertThat(waited).as("the rename waited for the row lock").isTrue();
        assertThat(outcomes.get(0)).isEqualTo(new GenreResponse(genreId, oldName, oldSlug));
        assertThat(renameTransactions.begun.get()).as("rename attempts").isEqualTo(1);
        assertThat(jdbc.query("select name, slug from genres where id = ?",
                (row, index) -> tuple(row.getString(1), row.getString(2)),
                genreId
        )).containsExactly(tuple(oldName, oldSlug));
    }

    // ---- helpers ----

    private CatalogWriteRetry writeRetry() {
        return new CatalogWriteRetry(transactionManager);
    }

    private MovieService movieService(GenreRepository genres, PersonRepository people, CatalogWriteRetry writeRetry) {
        return new MovieService(
                movieRepository, new MovieAssignmentResolver(genres, people), Clock.systemUTC(), writeRetry);
    }

    /**
     * A genre repository whose inserts run as usual, then call the hook.
     */
    private GenreRepository holdingGenres(Runnable hook) {
        GenreRepository genres = mock(GenreRepository.class, AdditionalAnswers.delegatesTo(genreRepository));
        doAnswer(invocation -> {
            int inserted = genreRepository.insertIfAbsent(
                    invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            hook.run();
            return inserted;
        }).when(genres).insertIfAbsent(any(), any(), any());
        return genres;
    }

    /**
     * A person repository whose inserts run as usual, then call the hook.
     */
    private PersonRepository holdingPeople(Runnable hook) {
        PersonRepository people = mock(PersonRepository.class, AdditionalAnswers.delegatesTo(personRepository));
        doAnswer(invocation -> {
            int inserted = personRepository.insertIfSlugFree(
                    invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            hook.run();
            return inserted;
        }).when(people).insertIfSlugFree(any(), any(), any());
        return people;
    }

    /**
     * Runs the create on another thread inside a transaction the test starts, so that the service
     * joins it and has a single attempt: a deadlock fails the create instead of being retried.
     */
    private Future<MovieDetail> createInOneAttempt(MovieService service, UpsertMovieRequest request) {
        return submit(() -> new TransactionTemplate(transactionManager).execute(status -> service.create(request)));
    }

    private <T> Future<T> submit(Callable<T> write) {
        return executor.submit(write);
    }

    private String existingGenre() {
        String genre = "Thể Loại " + token;
        inTransaction(() -> genreRepository.insertIfAbsent(UUID.randomUUID(), genre, SlugNormalizer.slugify(genre)));
        return genre;
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    /**
     * Runs the write in a transaction on another thread and returns once it is written; the
     * transaction commits when {@code release} opens. Fails with the write's own failure when the
     * write fails.
     */
    private Future<?> holdUncommitted(Runnable write, CountDownLatch release) throws Exception {
        CountDownLatch written = new CountDownLatch(1);
        Future<?> holder = submit(() -> {
            inTransaction(() -> {
                write.run();
                written.countDown();
                awaitRelease(release);
            });
            return null;
        });
        awaitCondition(() -> written.getCount() == 0 || holder.isDone());
        if (written.getCount() != 0) {
            if (holder.isDone()) {
                holder.get();
            }
            throw new AssertionError("The holder transaction did not write its rows");
        }
        return holder;
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The holder transaction was never released");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
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

    /**
     * Waits for both writes and returns, in order, each result or the API exception it ended with.
     * Fails with the SQLSTATE of every write that ended with any other failure.
     */
    private static List<Object> outcomes(Future<?> first, Future<?> second) throws Exception {
        List<Object> outcomes = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        List<Future<?>> writes = List.of(first, second);
        for (int index = 0; index < writes.size(); index++) {
            try {
                outcomes.add(writes.get(index).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            } catch (ExecutionException exception) {
                if (exception.getCause() instanceof ApiException apiException) {
                    outcomes.add(apiException);
                    continue;
                }
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
        return outcomes;
    }

    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
        }
        return "none";
    }

    /**
     * Polls the condition until it holds, and returns false when it never did before the timeout
     * or the thread was interrupted (the interrupt is kept).
     */
    private static boolean awaitCondition(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
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
     * Holds each of two writes after its first insert until the other write has inserted too, or
     * is waiting for a lock, so that both transactions are open with a row written when they go on.
     * A write that runs again after an abort is not held again.
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
            if (!awaitCondition(() -> inserted.getCount() == 0 || lockWaiters() > 0)) {
                timedOut.set(true);
            }
        }
    }

    /**
     * Holds the movie write after its first insert, lets the rename start, and lets the movie write
     * go on once the rename waits for a lock. A write that runs again after an abort is not held
     * again.
     */
    private final class Handoff {

        private final CountDownLatch inserted = new CountDownLatch(1);
        private final AtomicBoolean first = new AtomicBoolean(true);
        private final AtomicBoolean timedOut = new AtomicBoolean();

        void holdUntilBlocked() {
            if (!first.getAndSet(false)) {
                return;
            }
            inserted.countDown();
            if (!awaitCondition(() -> lockWaiters() > 0)) {
                timedOut.set(true);
            }
        }

        void awaitInserted() throws InterruptedException {
            if (!inserted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The movie write never inserted its row");
            }
        }

        void assertBlocked() {
            assertThat(inserted.getCount()).as("the movie write never inserted").isZero();
            assertThat(timedOut).as("the rename never waited for the movie write").isFalse();
        }
    }

    /**
     * Counts the transactions a {@link CatalogWriteRetry} begins, which are its attempts.
     */
    private static final class CountingTransactionManager implements PlatformTransactionManager {

        private final PlatformTransactionManager delegate;
        private final AtomicInteger begun = new AtomicInteger();

        CountingTransactionManager(PlatformTransactionManager delegate) {
            this.delegate = delegate;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            begun.incrementAndGet();
            return delegate.getTransaction(definition);
        }

        @Override
        public void commit(TransactionStatus status) {
            delegate.commit(status);
        }

        @Override
        public void rollback(TransactionStatus status) {
            delegate.rollback(status);
        }
    }
}
