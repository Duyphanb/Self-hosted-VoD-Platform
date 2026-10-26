package com.vodplatform.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.vodplatform.catalog.config.CatalogConfiguration;
import com.vodplatform.catalog.dto.GenreResponse;
import com.vodplatform.catalog.dto.UpsertGenreRequest;
import com.vodplatform.catalog.exception.CatalogValidationException;
import com.vodplatform.catalog.exception.GenreConflictException;
import com.vodplatform.catalog.exception.GenreNotFoundException;
import com.vodplatform.catalog.persistence.Genre;
import com.vodplatform.catalog.persistence.GenreRepository;
import com.vodplatform.catalog.persistence.Movie;
import com.vodplatform.catalog.persistence.MovieRepository;
import com.vodplatform.catalog.persistence.MovieStatus;
import com.vodplatform.shared.error.ApiException;
import com.vodplatform.shared.error.ApiFieldError;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ContextConfiguration;

/**
 * Admin genre management on the entity-generated H2 schema (PostgreSQL mode). Concurrent creation
 * between two transactions is covered by {@link CatalogConcurrencyTest}.
 */
@DataJpaTest
@ContextConfiguration(classes = {CatalogConfiguration.class, GenreService.class, CatalogWriteRetry.class})
class GenreServiceTest {

    @Autowired
    private GenreService genreService;

    @Autowired
    private GenreRepository genreRepository;

    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private CatalogWriteRetry writeRetry;

    // ---- create ----

    @Test
    void createStoresTheNormalizedNameAndItsSlug() {
        GenreResponse created = genreService.create(new UpsertGenreRequest("  Hành   Động "));
        flushAndClear();

        assertThat(created.id()).isNotNull();
        assertThat(created.name()).isEqualTo("Hành Động");
        assertThat(created.slug()).isEqualTo("hanh-dong");
        Genre stored = genreRepository.findById(created.id()).orElseThrow();
        assertThat(stored.getName()).isEqualTo("Hành Động");
        assertThat(stored.getSlug()).isEqualTo("hanh-dong");
    }

    @Test
    void createRejectsANameWhoseSlugAnotherGenreHas() {
        genreService.create(new UpsertGenreRequest("Hài"));
        flushAndClear();

        for (String variant : new String[] {"Hài", "HAI", " hai ", "Hải"}) {
            assertGenreConflict(catchThrowableOfType(
                    ApiException.class, () -> genreService.create(new UpsertGenreRequest(variant))));
        }
        assertThat(genreRepository.count()).isEqualTo(1);
    }

    @Test
    void createRejectsInvalidNamesWithTheContractMessages() {
        assertNameError(new UpsertGenreRequest(null), "must not be null");
        assertNameError(new UpsertGenreRequest("   "), "must not be blank");
        assertNameError(new UpsertGenreRequest("Hài\u0000"), "must not contain control characters");
        assertNameError(new UpsertGenreRequest("a".repeat(101)), "size must be between 1 and 100");
        assertNameError(new UpsertGenreRequest("!!! ★"), "must contain at least one letter or digit");

        assertThat(genreService.create(new UpsertGenreRequest("a".repeat(100))).slug()).hasSize(100);
        assertThat(genreRepository.count()).isEqualTo(1);
    }

    // ---- list ----

    @Test
    void listReturnsEveryGenreOrderedByNameThenId() {
        entityManager.persist(new Genre(uuid(3), "Drama", "drama"));
        entityManager.persist(new Genre(uuid(1), "Comedy", "comedy"));
        entityManager.persist(new Genre(uuid(2), "Action", "action"));
        flushAndClear();

        assertThat(genreService.list()).containsExactly(
                new GenreResponse(uuid(2), "Action", "action"),
                new GenreResponse(uuid(1), "Comedy", "comedy"),
                new GenreResponse(uuid(3), "Drama", "drama")
        );
    }

    // ---- rename ----

    @Test
    void renameRecomputesTheSlugAndKeepsTheMovieAssignments() {
        Genre genre = new Genre(uuid(1), "Hai", "hai");
        entityManager.persist(genre);
        Movie movie = new Movie(uuid(10), "Phim", "phim", "Mô tả", null, null, MovieStatus.PUBLISHED, Instant.EPOCH);
        movie.addGenre(genre);
        entityManager.persist(movie);
        flushAndClear();

        GenreResponse renamed = genreService.rename(uuid(1), new UpsertGenreRequest(" Hài   Hước "));
        flushAndClear();

        assertThat(renamed).isEqualTo(new GenreResponse(uuid(1), "Hài Hước", "hai-huoc"));
        assertThat(movieRepository.findById(uuid(10)).orElseThrow().getGenres())
                .extracting(Genre::getId, Genre::getName, Genre::getSlug)
                .containsExactly(tuple(uuid(1), "Hài Hước", "hai-huoc"));
    }

    @Test
    void renameMayKeepTheGenresOwnSlugButNotTakeAnotherGenresSlug() {
        entityManager.persist(new Genre(uuid(1), "hai", "hai"));
        entityManager.persist(new Genre(uuid(2), "Kinh Dị", "kinh-di"));
        flushAndClear();

        assertThat(genreService.rename(uuid(1), new UpsertGenreRequest("Hài")))
                .isEqualTo(new GenreResponse(uuid(1), "Hài", "hai"));
        assertGenreConflict(catchThrowableOfType(
                ApiException.class, () -> genreService.rename(uuid(1), new UpsertGenreRequest("kinh di"))));
    }

    @Test
    void renameReportsADuplicateWrittenAfterThePreCheckAsAConflict() {
        entityManager.persist(new Genre(uuid(1), "Hài", "hai"));
        entityManager.persist(new Genre(uuid(2), "Kinh Dị", "kinh-di"));
        flushAndClear();
        // The pre-check misses the other genre, as when another transaction commits it in between;
        // the flush then fails on a genre constraint, reported the way H2 names it.
        GenreRepository stale = mock(GenreRepository.class, AdditionalAnswers.delegatesTo(genreRepository));
        doReturn(false).when(stale).existsBySlugAndIdNot(anyString(), any());

        GenreService service = new GenreService(stale, writeRetry);
        ApiException conflict = catchThrowableOfType(
                ApiException.class, () -> service.rename(uuid(1), new UpsertGenreRequest("Kinh Dị")));

        assertGenreConflict(conflict);
    }

    @Test
    void renameOfAMissingGenreIsNotFoundButAnInvalidBodyIsReportedFirst() {
        ApiException missing = catchThrowableOfType(
                ApiException.class, () -> genreService.rename(uuid(99), new UpsertGenreRequest("Hài")));
        ApiException invalid = catchThrowableOfType(
                ApiException.class, () -> genreService.rename(uuid(99), new UpsertGenreRequest(" ")));

        assertThat(missing).isInstanceOf(GenreNotFoundException.class);
        assertThat(missing.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getCode()).isEqualTo("GENRE_NOT_FOUND");
        assertThat(missing.getMessage()).isEqualTo("Genre not found");
        assertThat(invalid).isInstanceOf(CatalogValidationException.class);
    }

    // ---- helpers ----

    private void assertNameError(UpsertGenreRequest request, String message) {
        CatalogValidationException invalid = catchThrowableOfType(
                CatalogValidationException.class, () -> genreService.create(request));

        assertThat(invalid).as("%s", request).isNotNull();
        assertThat(invalid.getCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(invalid.getFieldErrors())
                .extracting(ApiFieldError::field, ApiFieldError::message)
                .containsExactly(tuple("name", message));
    }

    private static void assertGenreConflict(ApiException exception) {
        assertThat(exception).isInstanceOf(GenreConflictException.class);
        assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exception.getCode()).isEqualTo("GENRE_CONFLICT");
        assertThat(exception.getFieldErrors()).isEmpty();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private static UUID uuid(int value) {
        return new UUID(0L, value);
    }
}
