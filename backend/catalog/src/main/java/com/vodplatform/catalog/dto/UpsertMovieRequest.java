package com.vodplatform.catalog.dto;

import com.vodplatform.catalog.persistence.MaturityRating;
import com.vodplatform.catalog.persistence.MovieStatus;
import java.util.List;

/**
 * Admin movie payload ({@code UpsertMovieRequest} in the API contract) for create and full
 * update. Values are kept as received, nulls included; {@code MovieService} validates and
 * normalizes them and reports every problem as a 400 {@code VALIDATION_ERROR} field error.
 *
 * @param genres genre names, not ids
 * @param people credits in display order; must be present, may be empty
 */
public record UpsertMovieRequest(
        String title,
        String slug,
        String description,
        Integer releaseYear,
        MaturityRating maturityRating,
        List<String> genres,
        List<MovieCreditInput> people,
        MovieStatus status
) {
}
