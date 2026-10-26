package com.vodplatform.catalog.service;

import com.vodplatform.catalog.dto.MovieDetail;
import com.vodplatform.catalog.dto.MovieSummary;
import com.vodplatform.catalog.dto.UpsertMovieRequest;
import com.vodplatform.catalog.exception.CatalogValidationException;
import com.vodplatform.catalog.exception.MovieNotFoundException;
import com.vodplatform.catalog.exception.MovieSlugConflictException;
import com.vodplatform.catalog.persistence.CatalogConstraints;
import com.vodplatform.catalog.persistence.CreditAssignment;
import com.vodplatform.catalog.persistence.Genre;
import com.vodplatform.catalog.persistence.Movie;
import com.vodplatform.catalog.persistence.MovieRepository;
import com.vodplatform.catalog.persistence.MovieSort;
import com.vodplatform.catalog.persistence.MovieStatus;
import com.vodplatform.catalog.service.MovieAssignmentResolver.ResolvedCredit;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Movie metadata CRUD (Issue 3.2). Returns catalog DTOs only; entities never leave this service.
 *
 * <p>Create and update validate the whole request first ({@link CatalogValidationException}), then
 * check the slug ({@link MovieSlugConflictException}) and resolve the credits, which only reads
 * (ADR-009 name matching included, see {@link MovieAssignmentResolver}). They then write the movie
 * row and flush it before resolving genres and creating the people of name-only credits, which may
 * insert rows, and finally write the genre and credit assignments. A duplicate slug that slips past
 * the pre-check because another transaction wrote it concurrently fails on {@code ux_movies_slug}
 * at that first flush and is reported as the same 409. Flushing the movie row first keeps that
 * true when both requests also bring the same new genre or person: the losing request fails on the
 * slug before it inserts anything else, so the 409 is never replaced by another outcome. Update
 * and archive lock the movie row first, so concurrent writes to one movie are serialized and the
 * last write wins.
 *
 * <p>No query may run between changing the movie row and that first flush: a query can auto-flush
 * pending changes, and a slug violation raised by such a flush would escape the 409 translation.
 * Requests for different movies that create the same new genre, or a person with the same slug,
 * do not fail: genres and people are inserted with {@code ON CONFLICT DO NOTHING}, so a genre
 * another request created first is reused and a taken person slug moves to the next suffix. Any
 * other integrity violation is rethrown unchanged, never reported as a slug conflict.
 *
 * <p>{@code search_vector} is not mapped and therefore never written here; search (Issue 7.2)
 * owns it.
 */
@Service
public class MovieService {

    /** Highest page index the API contract accepts. */
    public static final int MAX_PAGE = CatalogPaging.MAX_PAGE;

    /** Largest page size the API contract accepts. */
    public static final int MAX_PAGE_SIZE = CatalogPaging.MAX_PAGE_SIZE;

    private final MovieRepository movieRepository;
    private final MovieAssignmentResolver assignmentResolver;
    private final Clock clock;

    MovieService(MovieRepository movieRepository, MovieAssignmentResolver assignmentResolver, Clock clock) {
        this.movieRepository = movieRepository;
        this.assignmentResolver = assignmentResolver;
        this.clock = clock;
    }

    /**
     * Creates a movie with its genres and credits.
     *
     * @throws CatalogValidationException  400 {@code VALIDATION_ERROR}
     * @throws MovieSlugConflictException  409 {@code MOVIE_SLUG_CONFLICT}
     */
    @Transactional
    public MovieDetail create(UpsertMovieRequest request) {
        ValidatedMovie input = MovieRequestValidator.validate(request);
        if (movieRepository.existsBySlug(input.slug())) {
            throw new MovieSlugConflictException();
        }
        List<ResolvedCredit> credits = assignmentResolver.resolveCredits(input.people());

        // Insert the movie row before any new genre or person, so a concurrent duplicate slug is the
        // 409 even when the other request created the same new genre (see the class comment).
        Movie movie = writeOrSlugConflict(() -> movieRepository.saveAndFlush(new Movie(
                UUID.randomUUID(),
                input.title(),
                input.slug(),
                input.description(),
                input.releaseYear(),
                input.maturityRating(),
                input.status(),
                clock.instant()
        )));
        assignGenresAndCredits(movie, input, credits);
        return MovieMapper.toDetail(movie);
    }

    /**
     * Fully replaces a movie in any status: omitted optional fields become null, and genres and
     * credits replace the existing assignments. Setting {@code DRAFT} or {@code PUBLISHED}
     * restores an archived movie.
     *
     * @throws CatalogValidationException  400 {@code VALIDATION_ERROR}
     * @throws MovieNotFoundException      404 {@code MOVIE_NOT_FOUND}
     * @throws MovieSlugConflictException  409 {@code MOVIE_SLUG_CONFLICT}
     */
    @Transactional
    public MovieDetail update(UUID movieId, UpsertMovieRequest request) {
        Objects.requireNonNull(movieId, "movieId");
        ValidatedMovie input = MovieRequestValidator.validate(request);
        Movie movie = movieRepository.findForUpdateById(movieId).orElseThrow(MovieNotFoundException::new);
        if (movieRepository.existsBySlugAndIdNot(input.slug(), movieId)) {
            throw new MovieSlugConflictException();
        }
        // Resolve the credits before changing the movie: their lookup may auto-flush pending changes,
        // and a slug violation raised by that flush would escape the translation below.
        List<ResolvedCredit> credits = assignmentResolver.resolveCredits(input.people());

        movie.update(
                input.title(),
                input.slug(),
                input.description(),
                input.releaseYear(),
                input.maturityRating(),
                input.status(),
                clock.instant()
        );
        // Write the movie row before any new genre or person is inserted (see the class comment);
        // genres and people are resolved only after this flush, so their queries find nothing left
        // to auto-flush.
        writeOrSlugConflict(() -> {
            movieRepository.flush();
            return movie;
        });
        assignGenresAndCredits(movie, input, credits);
        return MovieMapper.toDetail(movie);
    }

    /**
     * Resolves the genres and creates the people of name-only credits, which may insert rows, then
     * replaces the movie's assignments and writes them. Call it only after the movie row is flushed.
     */
    private void assignGenresAndCredits(Movie movie, ValidatedMovie input, List<ResolvedCredit> credits) {
        List<Genre> genres = assignmentResolver.resolveGenres(input.genres());
        List<CreditAssignment> assignments = assignmentResolver.assignCredits(credits);
        movie.replaceGenres(genres);
        movie.replaceCredits(assignments);
        movieRepository.flush();
    }

    /**
     * The catalog visibility check (ADR-009, {@code SECURITY.md#movie-visibility}): returns the
     * movie when the visibility includes its status. A missing movie and a movie the caller may
     * not see raise the same {@link MovieNotFoundException}, so the response never reveals that a
     * {@code DRAFT} or {@code ARCHIVED} movie exists. Other modules reuse this check with
     * {@link MovieVisibility#PUBLISHED_ONLY} instead of testing the status themselves.
     *
     * @throws MovieNotFoundException 404 {@code MOVIE_NOT_FOUND}
     */
    @Transactional(readOnly = true)
    public MovieDetail getMovie(UUID movieId, MovieVisibility visibility) {
        Objects.requireNonNull(movieId, "movieId");
        Objects.requireNonNull(visibility, "visibility");
        Movie movie = movieRepository.findById(movieId)
                .filter(found -> visibility.includes(found.getStatus()))
                .orElseThrow(MovieNotFoundException::new);
        return MovieMapper.toDetail(movie);
    }

    /**
     * User-facing list: {@code PUBLISHED} movies only, for every role.
     *
     * @param page zero-based page index, at most {@value #MAX_PAGE}
     * @param size page size, from 1 to {@value #MAX_PAGE_SIZE}
     * @throws IllegalArgumentException if the page or size is outside the contract range; the API
     *                                  layer validates them first
     */
    @Transactional(readOnly = true)
    public Page<MovieSummary> listPublished(int page, int size, MovieSort sort) {
        return movieRepository.findPublished(null, null, pageRequest(page, size, sort)).map(MovieMapper::toSummary);
    }

    /**
     * Admin list in any status.
     *
     * @param status the status to list, or null for every status
     * @param page   zero-based page index, at most {@value #MAX_PAGE}
     * @param size   page size, from 1 to {@value #MAX_PAGE_SIZE}
     * @throws IllegalArgumentException if the page or size is outside the contract range; the API
     *                                  layer validates them first
     */
    @Transactional(readOnly = true)
    public Page<MovieSummary> listForAdmin(MovieStatus status, int page, int size, MovieSort sort) {
        Pageable pageable = pageRequest(page, size, sort);
        Page<Movie> movies = status == null
                ? movieRepository.findAll(pageable)
                : movieRepository.findByStatus(status, pageable);
        return movies.map(MovieMapper::toSummary);
    }

    /**
     * Archives a movie in any status (soft delete). Idempotent: archiving an archived movie
     * succeeds and changes nothing.
     *
     * @throws MovieNotFoundException 404 {@code MOVIE_NOT_FOUND}
     */
    @Transactional
    public void archive(UUID movieId) {
        Objects.requireNonNull(movieId, "movieId");
        Movie movie = movieRepository.findForUpdateById(movieId).orElseThrow(MovieNotFoundException::new);
        movie.archive(clock.instant());
    }

    private static Pageable pageRequest(int page, int size, MovieSort sort) {
        Objects.requireNonNull(sort, "sort");
        return CatalogPaging.pageRequest(page, size, sort.sort());
    }

    /**
     * Runs a write that flushes, reporting a {@code ux_movies_slug} violation as 409. Any other
     * integrity violation is rethrown unchanged.
     */
    private static Movie writeOrSlugConflict(Supplier<Movie> write) {
        try {
            return write.get();
        } catch (DataIntegrityViolationException exception) {
            if (CatalogConstraints.isViolationOf(exception, CatalogConstraints.MOVIES_SLUG)) {
                throw new MovieSlugConflictException();
            }
            throw exception;
        }
    }
}
