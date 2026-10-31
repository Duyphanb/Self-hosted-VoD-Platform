package com.vodplatform.catalog.service;

import static com.vodplatform.catalog.persistence.CreditRole.ACTOR;
import static com.vodplatform.catalog.service.CatalogWriteRetryTest.deadlock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.vodplatform.catalog.config.CatalogConfiguration;
import com.vodplatform.catalog.dto.CreditedPerson;
import com.vodplatform.catalog.dto.GenreResponse;
import com.vodplatform.catalog.dto.MovieCreditInput;
import com.vodplatform.catalog.dto.MovieDetail;
import com.vodplatform.catalog.dto.PersonResponse;
import com.vodplatform.catalog.dto.UpsertGenreRequest;
import com.vodplatform.catalog.dto.UpsertPersonRequest;
import com.vodplatform.catalog.exception.GenreConflictException;
import com.vodplatform.catalog.exception.MovieNotFoundException;
import com.vodplatform.catalog.exception.MovieSlugConflictException;
import com.vodplatform.catalog.exception.PersonNotFoundException;
import com.vodplatform.catalog.persistence.Genre;
import com.vodplatform.catalog.persistence.GenreRepository;
import com.vodplatform.catalog.persistence.Movie;
import com.vodplatform.catalog.persistence.MovieRepository;
import com.vodplatform.catalog.persistence.MovieStatus;
import com.vodplatform.catalog.persistence.Person;
import com.vodplatform.catalog.persistence.PersonRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link CatalogWriteRetry} with real transactions on H2, and the catalog writes that run through
 * it. A deadlock is injected after a write has really run in the attempt's transaction, so the
 * next attempt shows whether anything of the failed one was left behind. The deadlock itself, a
 * PostgreSQL abort, is reproduced by {@link CreationOrderPostgresTest}.
 *
 * <p>The test methods run without a test transaction, so that every write starts its own.
 */
@DataJpaTest
@ContextConfiguration(classes = {CatalogConfiguration.class, CatalogWriteRetry.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CatalogWriteRetryTransactionTest {

    private static final Instant T0 = Instant.parse("2026-10-01T08:00:00Z");

    @Autowired
    private CatalogWriteRetry writeRetry;

    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private GenreRepository genreRepository;

    @Autowired
    private PersonRepository personRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    private final MutableClock clock = new MutableClock(T0);
    private JdbcTemplate jdbc;

    @BeforeEach
    void start() {
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void cleanUp() {
        for (String table : List.of("movie_credits", "movie_genres", "movies", "genres", "people")) {
            jdbc.update("delete from " + table);
        }
    }

    // ---- the retry ----

    @Test
    void eachAttemptRunsInANewTransactionThatSeesNothingOfTheFailedOne() {
        List<Integer> completions = new ArrayList<>();
        List<EntityManager> persistenceContexts = new ArrayList<>();
        List<Boolean> genreVisible = new ArrayList<>();
        List<Boolean> entityManaged = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        Genre written = new Genre(uuid(1), "Kinh Dị", "kinh-di");

        int inserted = writeRetry.execute(() -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    completions.add(status);
                }
            });
            persistenceContexts.add(EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory));
            genreVisible.add(genreRepository.findBySlug("kinh-di").isPresent());
            entityManaged.add(entityManager.contains(written));
            if (attempts.incrementAndGet() == 1) {
                entityManager.persist(written);
                entityManager.flush();
                throw deadlock();
            }
            return genreRepository.insertIfAbsent(uuid(2), "Kinh Dị", "kinh-di");
        });

        assertThat(inserted).isEqualTo(1);
        assertThat(completions).containsExactly(
                TransactionSynchronization.STATUS_ROLLED_BACK, TransactionSynchronization.STATUS_COMMITTED);
        assertThat(persistenceContexts).hasSize(2).doesNotContainNull();
        assertThat(persistenceContexts.get(1)).isNotSameAs(persistenceContexts.get(0));
        // Neither the row nor the entity the failed attempt wrote is seen by the next one.
        assertThat(genreVisible).containsExactly(false, false);
        assertThat(entityManaged).containsExactly(false, false);
        assertThat(jdbc.queryForList("select id from genres", UUID.class)).containsExactly(uuid(2));
    }

    // ---- the catalog writes ----

    @Test
    void aMovieCreateAbortedAfterItsFirstPersonInsertIsWrittenOnceByTheRetry() {
        PersonRepository people = mock(PersonRepository.class, AdditionalAnswers.delegatesTo(personRepository));
        AtomicInteger personInserts = new AtomicInteger();
        doAnswer(invocation -> {
            int inserted = personRepository.insertIfSlugFree(
                    invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            if (personInserts.incrementAndGet() == 1) {
                throw deadlock();
            }
            return inserted;
        }).when(people).insertIfSlugFree(any(), any(), any());
        MovieRepository movies = mock(MovieRepository.class, AdditionalAnswers.delegatesTo(movieRepository));
        MovieService service = new MovieService(
                movies, new MovieAssignmentResolver(genreRepository, people), clock, writeRetry,
                movieIds -> java.util.Map.of());

        MovieDetail created = service.create(new MovieRequestBuilder()
                .slug("phim")
                .genres(List.of("Kinh Dị"))
                .people(List.of(new MovieCreditInput(null, "Lý Hải", ACTOR)))
                .build());

        verify(movies, times(2)).saveAndFlush(any(Movie.class));
        UUID personId = jdbc.queryForObject("select id from people", UUID.class);
        assertThat(created.slug()).isEqualTo("phim");
        assertThat(created.genres()).containsExactly("Kinh Dị");
        assertThat(created.people()).containsExactly(new CreditedPerson(personId, "Lý Hải", ACTOR));
        // The rows of the aborted attempt were rolled back: the person kept the base slug, and the
        // movie, the genre and the assignments exist once.
        assertThat(jdbc.queryForList("select slug from people", String.class)).containsExactly("ly-hai");
        assertThat(jdbc.queryForList("select id from movies", UUID.class)).containsExactly(created.id());
        assertThat(jdbc.queryForList("select slug from genres", String.class)).containsExactly("kinh-di");
        assertThat(jdbc.queryForObject("select count(*) from movie_genres", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from movie_credits", Long.class)).isEqualTo(1);
    }

    @Test
    void aMovieUpdateAbortedAfterItsFirstGenreInsertIsWrittenOnceByTheRetry() {
        inTransaction(() -> {
            Genre comedy = new Genre(uuid(1), "Hài", "hai");
            entityManager.persist(comedy);
            Movie movie = new Movie(uuid(10), "Phim", "phim", "Mô tả", null, null, MovieStatus.DRAFT, Instant.EPOCH);
            movie.addGenre(comedy);
            entityManager.persist(movie);
        });
        GenreRepository genres = mock(GenreRepository.class, AdditionalAnswers.delegatesTo(genreRepository));
        AtomicInteger genreInserts = new AtomicInteger();
        doAnswer(invocation -> {
            int inserted = genreRepository.insertIfAbsent(
                    invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            if (genreInserts.incrementAndGet() == 1) {
                throw deadlock();
            }
            return inserted;
        }).when(genres).insertIfAbsent(any(), any(), any());
        MovieRepository movies = mock(MovieRepository.class, AdditionalAnswers.delegatesTo(movieRepository));
        MovieService service = new MovieService(
                movies, new MovieAssignmentResolver(genres, personRepository), clock, writeRetry,
                movieIds -> java.util.Map.of());

        MovieDetail updated = service.update(uuid(10), new MovieRequestBuilder()
                .title("Phim Mới")
                .slug("phim-moi")
                .genres(List.of("Kinh Dị"))
                .build());

        verify(movies, times(2)).findForUpdateById(uuid(10));
        assertThat(updated.id()).isEqualTo(uuid(10));
        assertThat(updated.title()).isEqualTo("Phim Mới");
        assertThat(updated.slug()).isEqualTo("phim-moi");
        assertThat(updated.genres()).containsExactly("Kinh Dị");
        assertThat(jdbc.queryForList("select slug from movies", String.class)).containsExactly("phim-moi");
        assertThat(jdbc.queryForList("select slug from genres order by slug", String.class))
                .containsExactly("hai", "kinh-di");
        assertThat(jdbc.queryForList(
                "select g.slug from movie_genres mg join genres g on g.id = mg.genre_id", String.class))
                .containsExactly("kinh-di");
    }

    @Test
    void aPersonCreateAbortedAfterItsInsertKeepsTheBaseSlugOnTheRetry() {
        PersonRepository people = mock(PersonRepository.class, AdditionalAnswers.delegatesTo(personRepository));
        AtomicInteger inserts = new AtomicInteger();
        doAnswer(invocation -> {
            int inserted = personRepository.insertIfSlugFree(
                    invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            if (inserts.incrementAndGet() == 1) {
                throw deadlock();
            }
            return inserted;
        }).when(people).insertIfSlugFree(any(), any(), any());

        PersonResponse created = new PersonService(people, writeRetry)
                .create(new UpsertPersonRequest(" Trấn  Thành "));

        assertThat(inserts).hasValue(2);
        assertThat(created.name()).isEqualTo("Trấn Thành");
        assertThat(created.slug()).isEqualTo("tran-thanh");
        assertThat(jdbc.queryForList("select id from people", UUID.class)).containsExactly(created.id());
    }

    @Test
    void aPersonRenameAbortedAfterItsUpdateIsRenamedOnceByTheRetry() {
        inTransaction(() -> entityManager.persist(new Person(uuid(1), "Lý Hải", "ly-hai")));
        PersonRepository people = mock(PersonRepository.class, AdditionalAnswers.delegatesTo(personRepository));
        AtomicInteger renames = new AtomicInteger();
        doAnswer(invocation -> {
            boolean renamed = personRepository.renameIfSlugFree(
                    invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            if (renames.incrementAndGet() == 1) {
                throw deadlock();
            }
            return renamed;
        }).when(people).renameIfSlugFree(any(), any(), any());

        PersonResponse renamed = new PersonService(people, writeRetry)
                .rename(uuid(1), new UpsertPersonRequest("Trấn Thành"));

        verify(people, times(2)).findForUpdateById(uuid(1));
        assertThat(renamed).isEqualTo(new PersonResponse(uuid(1), "Trấn Thành", "tran-thanh"));
        assertThat(jdbc.query("select id, name, slug from people",
                (row, index) -> tuple(row.getObject(1, UUID.class), row.getString(2), row.getString(3))))
                .containsExactly(tuple(uuid(1), "Trấn Thành", "tran-thanh"));
    }

    @Test
    void aGenreCreateAbortedAfterItsInsertIsCreatedByTheRetryRatherThanAConflict() {
        GenreRepository genres = mock(GenreRepository.class, AdditionalAnswers.delegatesTo(genreRepository));
        AtomicInteger inserts = new AtomicInteger();
        doAnswer(invocation -> {
            int inserted = genreRepository.insertIfAbsent(
                    invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            if (inserts.incrementAndGet() == 1) {
                throw deadlock();
            }
            return inserted;
        }).when(genres).insertIfAbsent(any(), any(), any());

        GenreResponse created = new GenreService(genres, writeRetry).create(new UpsertGenreRequest("Kinh  Dị"));

        assertThat(inserts).hasValue(2);
        assertThat(created.name()).isEqualTo("Kinh Dị");
        assertThat(created.slug()).isEqualTo("kinh-di");
        assertThat(jdbc.queryForList("select id from genres", UUID.class)).containsExactly(created.id());
    }

    @Test
    void aGenreRenameAbortedAtItsFlushIsRenamedOnceByTheRetry() {
        inTransaction(() -> entityManager.persist(new Genre(uuid(1), "Hai", "hai")));
        GenreRepository genres = mock(GenreRepository.class, AdditionalAnswers.delegatesTo(genreRepository));
        AtomicInteger flushes = new AtomicInteger();
        doAnswer(invocation -> {
            genreRepository.flush();
            if (flushes.incrementAndGet() == 1) {
                throw deadlock();
            }
            return null;
        }).when(genres).flush();

        GenreResponse renamed = new GenreService(genres, writeRetry)
                .rename(uuid(1), new UpsertGenreRequest("Hài Hước"));

        verify(genres, times(2)).findForUpdateById(uuid(1));
        assertThat(renamed).isEqualTo(new GenreResponse(uuid(1), "Hài Hước", "hai-huoc"));
        assertThat(jdbc.query("select id, name, slug from genres",
                (row, index) -> tuple(row.getObject(1, UUID.class), row.getString(2), row.getString(3))))
                .containsExactly(tuple(uuid(1), "Hài Hước", "hai-huoc"));
    }

    @Test
    void notFoundAndConflictOutcomesAreNotRetried() {
        inTransaction(() -> {
            entityManager.persist(new Genre(uuid(1), "Hài", "hai"));
            entityManager.persist(
                    new Movie(uuid(10), "Phim", "phim", "Mô tả", null, null, MovieStatus.DRAFT, Instant.EPOCH));
        });
        MovieRepository movies = mock(MovieRepository.class, AdditionalAnswers.delegatesTo(movieRepository));
        GenreRepository genres = mock(GenreRepository.class, AdditionalAnswers.delegatesTo(genreRepository));
        PersonRepository people = mock(PersonRepository.class, AdditionalAnswers.delegatesTo(personRepository));
        MovieService movieService = new MovieService(
                movies, new MovieAssignmentResolver(genreRepository, personRepository), clock, writeRetry,
                movieIds -> java.util.Map.of());

        assertThat(catchThrowable(() -> movieService.create(new MovieRequestBuilder().slug("phim").build())))
                .isInstanceOf(MovieSlugConflictException.class);
        assertThat(catchThrowable(() -> movieService.update(uuid(99), new MovieRequestBuilder().build())))
                .isInstanceOf(MovieNotFoundException.class);
        assertThat(catchThrowable(() -> new GenreService(genres, writeRetry).create(new UpsertGenreRequest("HÀI"))))
                .isInstanceOf(GenreConflictException.class);
        assertThat(catchThrowable(
                () -> new PersonService(people, writeRetry).rename(uuid(99), new UpsertPersonRequest("Lý Hải"))))
                .isInstanceOf(PersonNotFoundException.class);

        verify(movies, times(1)).existsBySlug("phim");
        verify(movies, times(1)).findForUpdateById(uuid(99));
        verify(genres, times(1)).insertIfAbsent(any(), any(), any());
        verify(people, times(1)).findForUpdateById(uuid(99));
    }

    // ---- helpers ----

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    private static UUID uuid(int value) {
        return new UUID(0L, value);
    }
}
