package com.vodplatform.catalog.web;

import com.vodplatform.catalog.dto.MovieDetailResponse;
import com.vodplatform.catalog.dto.MoviePageResponse;
import com.vodplatform.catalog.dto.UpsertMovieRequest;
import com.vodplatform.catalog.persistence.MovieSort;
import com.vodplatform.catalog.service.CatalogPaging;
import com.vodplatform.catalog.service.EpisodeProgressLookup;
import com.vodplatform.catalog.service.MovieService;
import com.vodplatform.catalog.service.MovieVisibility;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Title endpoints (ADR-013 D11): the viewer list and detail under {@code /api/v1/titles} and the admin
 * detail and writes under {@code /api/v1/admin/titles}.
 *
 * <p>The viewer list and detail hold {@code PUBLISHED} titles only, for every role including
 * {@code ROLE_ADMIN}, so their result does not depend on the caller (ADR-013 D3,
 * {@code SECURITY.md#title-and-episode-visibility}). A hidden title gets the same 404
 * {@code TITLE_NOT_FOUND} as a missing one. The viewer detail of a {@code MOVIE} title embeds its single
 * episode with the caller's progress, so a film page needs one request. Only the contract's query
 * parameters are bound; Spring Data {@code Pageable} and {@code Sort} are not. Page, size and year ranges
 * are validated here, {@code sort} by {@link MovieSortBinding}, and the genre filter by
 * {@link MovieService}.
 *
 * <p>The admin detail, create, update and archive require {@code ROLE_ADMIN} and manage titles in every
 * status. The {@code /api/v1/admin/**} URL rule authorizes them first, before the path variable and body
 * are bound, so any other caller gets 403 even for an invalid body or a missing title;
 * {@code @PreAuthorize} is the second layer. {@link MovieService} validates the body and reports every
 * problem as 400 {@code VALIDATION_ERROR}, a slug used by another title as 409
 * {@code TITLE_SLUG_CONFLICT}, and a missing title as 404 {@code TITLE_NOT_FOUND}. Archive is a soft
 * delete and idempotent.
 */
@RestController
public class MovieController {
    private final MovieService movieService;
    private final EpisodeProgressLookup progress;

    public MovieController(MovieService movieService, EpisodeProgressLookup progress) {
        this.movieService = movieService;
        this.progress = progress;
    }

    @GetMapping("/api/v1/titles")
    public MoviePageResponse list(
            @RequestParam(name = "page", defaultValue = "0")
            @Min(value = 0, message = CatalogParameters.PAGE_RANGE)
            @Max(value = CatalogPaging.MAX_PAGE, message = CatalogParameters.PAGE_RANGE) int page,
            @RequestParam(name = "size", defaultValue = "" + CatalogPaging.DEFAULT_PAGE_SIZE)
            @Min(value = 1, message = CatalogParameters.SIZE_RANGE)
            @Max(value = CatalogPaging.MAX_PAGE_SIZE, message = CatalogParameters.SIZE_RANGE) int size,
            @RequestParam(name = "genre", required = false) String genre,
            @RequestParam(name = "year", required = false)
            @Min(value = MovieService.MIN_RELEASE_YEAR, message = CatalogParameters.YEAR_RANGE)
            @Max(value = MovieService.MAX_RELEASE_YEAR, message = CatalogParameters.YEAR_RANGE) Integer year,
            @RequestParam(name = "sort", defaultValue = "latest") MovieSort sort
    ) {
        return MoviePageResponse.of(movieService.listPublished(genre, year, page, size, sort));
    }

    @GetMapping("/api/v1/titles/{titleId}")
    public MovieDetailResponse get(@PathVariable UUID titleId, Authentication authentication) {
        var title = movieService.getMovie(titleId, MovieVisibility.PUBLISHED_ONLY);
        var episode = title.episode();
        return MovieDetailResponse.of(title, episode == null ? null
                : progress.find(UUID.fromString(authentication.getName()), episode.id()));
    }

    @GetMapping("/api/v1/admin/titles/{titleId}")
    @PreAuthorize("hasRole('ADMIN')")
    public MovieDetailResponse adminGet(@PathVariable UUID titleId) {
        return MovieDetailResponse.of(movieService.getMovie(titleId, MovieVisibility.ANY_STATUS));
    }

    @PostMapping("/api/v1/admin/titles")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<MovieDetailResponse> create(@RequestBody UpsertMovieRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(MovieDetailResponse.of(movieService.create(request)));
    }

    /**
     * Full replacement of a movie in any status; {@code DRAFT} or {@code PUBLISHED} restores an
     * archived movie.
     */
    @PutMapping("/api/v1/admin/titles/{titleId}")
    @PreAuthorize("hasRole('ADMIN')")
    public MovieDetailResponse update(@PathVariable UUID titleId, @RequestBody UpsertMovieRequest request) {
        return MovieDetailResponse.of(movieService.update(titleId, request));
    }

    /**
     * Archives a movie in any status (soft delete); archiving an archived movie also returns 204.
     */
    @DeleteMapping("/api/v1/admin/titles/{titleId}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable UUID titleId) {
        movieService.archive(titleId);
    }
}
