package com.vodplatform.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vodplatform.catalog.exception.CatalogValidationException;
import com.vodplatform.catalog.exception.MovieSlugConflictException;
import com.vodplatform.catalog.persistence.Movie;
import com.vodplatform.catalog.persistence.MovieRepository;
import com.vodplatform.catalog.persistence.MovieStatus;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Error translation and call order of {@link MovieService} without a database. Violations are
 * reported with the bare constraint name, as PostgreSQL does; the H2 form is covered by
 * {@link MovieServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class MovieServiceUnitTest {

    private static final Instant T0 = Instant.parse("2026-10-01T08:00:00Z");
    private static final UUID MOVIE_ID = new UUID(0L, 1);

    @Mock
    private MovieRepository movieRepository;

    @Mock
    private MovieAssignmentResolver assignmentResolver;

    private final MutableClock clock = new MutableClock(T0);

    private MovieService movieService;

    @BeforeEach
    void createService() {
        movieService = new MovieService(movieRepository, assignmentResolver, clock);
    }

    @Test
    void aSlugViolationReportedByPostgresqlOnCreateIsAConflict() {
        when(movieRepository.saveAndFlush(any(Movie.class))).thenThrow(violation("ux_movies_slug"));

        Throwable failure = catchThrowable(() -> movieService.create(new MovieRequestBuilder().build()));

        assertThat(failure).isInstanceOf(MovieSlugConflictException.class);
    }

    @Test
    void aSlugViolationReportedByPostgresqlOnUpdateIsAConflict() {
        when(movieRepository.findForUpdateById(MOVIE_ID)).thenReturn(Optional.of(movie(MovieStatus.PUBLISHED)));
        doThrow(violation("ux_movies_slug")).when(movieRepository).flush();

        Throwable failure = catchThrowable(() -> movieService.update(MOVIE_ID, new MovieRequestBuilder().build()));

        assertThat(failure).isInstanceOf(MovieSlugConflictException.class);
    }

    @Test
    void otherIntegrityViolationsAreNotReportedAsSlugConflicts() {
        DataIntegrityViolationException genreRace = violation("ux_genres_slug");
        when(movieRepository.saveAndFlush(any(Movie.class))).thenThrow(genreRace);

        Throwable failure = catchThrowable(() -> movieService.create(new MovieRequestBuilder().build()));

        assertThat(failure).isSameAs(genreRace);
    }

    @Test
    void aGenreViolationAfterTheMovieIsCreatedIsNotReportedAsASlugConflict() {
        DataIntegrityViolationException genreRace = violation("ux_genres_name");
        when(movieRepository.saveAndFlush(any(Movie.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(genreRace).when(movieRepository).flush();

        Throwable failure = catchThrowable(() -> movieService.create(new MovieRequestBuilder().build()));

        assertThat(failure).isSameAs(genreRace);
    }

    @Test
    void aGenreViolationAfterTheMovieIsUpdatedIsNotReportedAsASlugConflict() {
        DataIntegrityViolationException genreRace = violation("ux_genres_slug");
        when(movieRepository.findForUpdateById(MOVIE_ID)).thenReturn(Optional.of(movie(MovieStatus.PUBLISHED)));
        doNothing().doThrow(genreRace).when(movieRepository).flush();

        Throwable failure = catchThrowable(() -> movieService.update(MOVIE_ID, new MovieRequestBuilder().build()));

        assertThat(failure).isSameAs(genreRace);
    }

    @Test
    void creditsAreResolvedBeforeAndGenresAfterTheMovieRowIsWritten() {
        when(movieRepository.saveAndFlush(any(Movie.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(movieRepository.findForUpdateById(MOVIE_ID)).thenReturn(Optional.of(movie(MovieStatus.PUBLISHED)));
        InOrder order = inOrder(movieRepository, assignmentResolver);

        movieService.create(new MovieRequestBuilder().build());

        order.verify(movieRepository).existsBySlug(anyString());
        order.verify(assignmentResolver).resolveCredits(anyList());
        order.verify(movieRepository).saveAndFlush(any(Movie.class));
        order.verify(assignmentResolver).resolveGenres(anyList());
        order.verify(movieRepository).flush();

        movieService.update(MOVIE_ID, new MovieRequestBuilder().build());

        order.verify(movieRepository).findForUpdateById(MOVIE_ID);
        order.verify(movieRepository).existsBySlugAndIdNot(anyString(), any(UUID.class));
        order.verify(assignmentResolver).resolveCredits(anyList());
        order.verify(movieRepository).flush();
        order.verify(assignmentResolver).resolveGenres(anyList());
        order.verify(movieRepository).flush();
        order.verifyNoMoreInteractions();
    }

    @Test
    void invalidRequestsDoNotReachTheDatabase() {
        assertThat(catchThrowable(() -> movieService.create(new MovieRequestBuilder().slug("Not A Slug").build())))
                .isInstanceOf(CatalogValidationException.class);
        assertThat(catchThrowable(() -> movieService.update(MOVIE_ID, new MovieRequestBuilder().genres(List.of()).build())))
                .isInstanceOf(CatalogValidationException.class);

        verifyNoInteractions(movieRepository, assignmentResolver);
    }

    @Test
    void aSlugConflictFoundByThePreCheckStopsBeforeGenresAndCreditsAreResolved() {
        when(movieRepository.findForUpdateById(MOVIE_ID)).thenReturn(Optional.of(movie(MovieStatus.DRAFT)));
        when(movieRepository.existsBySlugAndIdNot(anyString(), any(UUID.class))).thenReturn(true);

        assertThat(catchThrowable(() -> movieService.update(MOVIE_ID, new MovieRequestBuilder().build())))
                .isInstanceOf(MovieSlugConflictException.class);
        verifyNoInteractions(assignmentResolver);
        verify(movieRepository, never()).flush();
    }

    @Test
    void writesLoadTheMovieWithTheRowLockAndReadsDoNot() {
        Movie archived = movie(MovieStatus.ARCHIVED);
        when(movieRepository.findForUpdateById(MOVIE_ID)).thenReturn(Optional.of(archived));
        when(movieRepository.findById(MOVIE_ID)).thenReturn(Optional.of(archived));
        clock.advance(Duration.ofHours(1));

        movieService.archive(MOVIE_ID);
        movieService.getMovie(MOVIE_ID, MovieVisibility.ANY_STATUS);

        verify(movieRepository).findForUpdateById(MOVIE_ID);
        verify(movieRepository).findById(MOVIE_ID);
        // Archiving an archived movie changes nothing.
        assertThat(archived.getUpdatedAt()).isEqualTo(T0);
    }

    private static Movie movie(MovieStatus status) {
        return new Movie(MOVIE_ID, "Phim", "phim", "Mô tả", null, null, status, T0);
    }

    private static DataIntegrityViolationException violation(String constraintName) {
        return new DataIntegrityViolationException(
                "could not execute statement",
                new ConstraintViolationException("could not execute statement", new SQLException("duplicate"), constraintName)
        );
    }
}
