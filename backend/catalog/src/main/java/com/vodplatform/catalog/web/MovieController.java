package com.vodplatform.catalog.web;

import com.vodplatform.catalog.dto.MovieDetailResponse;
import com.vodplatform.catalog.dto.MoviePageResponse;
import com.vodplatform.catalog.persistence.MovieSort;
import com.vodplatform.catalog.service.CatalogPaging;
import com.vodplatform.catalog.service.MovieService;
import com.vodplatform.catalog.service.MovieVisibility;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Movie reads for every authenticated caller ({@code /api/v1/movies}). The list holds
 * {@code PUBLISHED} movies only, for every role, so its result does not depend on the caller.
 * The detail applies the movie visibility rule (ADR-009, {@code SECURITY.md#movie-visibility}):
 * {@code ROLE_ADMIN} reads a movie in any status, and every other caller gets the same 404
 * {@code MOVIE_NOT_FOUND} for a non-published movie as for a missing one.
 *
 * <p>Only the contract's query parameters are bound; Spring Data {@code Pageable} and
 * {@code Sort} are not. Page, size and year ranges are validated here, {@code sort} by
 * {@link MovieSortBinding}, and the genre filter by {@link MovieService}.
 */
@RestController
@RequestMapping("/api/v1/movies")
public class MovieController {

    private static final String ADMIN_ROLE = "ROLE_ADMIN";

    private final MovieService movieService;

    public MovieController(MovieService movieService) {
        this.movieService = movieService;
    }

    @GetMapping
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

    @GetMapping("/{movieId}")
    public MovieDetailResponse get(@PathVariable UUID movieId, Authentication authentication) {
        return MovieDetailResponse.of(movieService.getMovie(movieId, visibilityFor(authentication)));
    }

    /**
     * {@code GET /api/v1/movies/{movieId}} is one of the admin exceptions to the 404 rule; every
     * other caller sees published movies only.
     */
    private static MovieVisibility visibilityFor(Authentication authentication) {
        boolean admin = authentication != null && authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ADMIN_ROLE::equals);
        return admin ? MovieVisibility.ANY_STATUS : MovieVisibility.PUBLISHED_ONLY;
    }
}
