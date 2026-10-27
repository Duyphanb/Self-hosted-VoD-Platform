package com.vodplatform.catalog.web;

import com.vodplatform.catalog.dto.MoviePageResponse;
import com.vodplatform.catalog.persistence.MovieSort;
import com.vodplatform.catalog.persistence.MovieStatus;
import com.vodplatform.catalog.service.CatalogPaging;
import com.vodplatform.catalog.service.MovieService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin movie list in any status ({@code GET /api/v1/admin/movies}). The URL rule for
 * {@code /api/v1/admin/**} authorizes {@code ROLE_ADMIN} first; {@code @PreAuthorize} is the
 * second layer. Without {@code status}, movies in every status are listed. Only the contract's
 * query parameters are bound; Spring Data {@code Pageable} and {@code Sort} are not.
 */
@RestController
@RequestMapping("/api/v1/admin/movies")
@PreAuthorize("hasRole('ADMIN')")
public class MovieAdminController {

    private final MovieService movieService;

    public MovieAdminController(MovieService movieService) {
        this.movieService = movieService;
    }

    @GetMapping
    public MoviePageResponse list(
            @RequestParam(name = "status", required = false) MovieStatus status,
            @RequestParam(name = "page", defaultValue = "0")
            @Min(value = 0, message = CatalogParameters.PAGE_RANGE)
            @Max(value = CatalogPaging.MAX_PAGE, message = CatalogParameters.PAGE_RANGE) int page,
            @RequestParam(name = "size", defaultValue = "" + CatalogPaging.DEFAULT_PAGE_SIZE)
            @Min(value = 1, message = CatalogParameters.SIZE_RANGE)
            @Max(value = CatalogPaging.MAX_PAGE_SIZE, message = CatalogParameters.SIZE_RANGE) int size,
            @RequestParam(name = "sort", defaultValue = "latest") MovieSort sort
    ) {
        return MoviePageResponse.of(movieService.listForAdmin(status, page, size, sort));
    }
}
