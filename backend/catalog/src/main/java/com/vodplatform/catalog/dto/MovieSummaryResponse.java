package com.vodplatform.catalog.dto;

import com.vodplatform.catalog.persistence.MovieStatus;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A movie list item as the API returns it ({@code MovieSummary} in the API contract): the catalog
 * fields of a {@link MovieSummary} plus the fields owned by other modules.
 *
 * @param posterUrl     always null: posters are deferred (ERD {@code poster_object_key})
 * @param genres        genre names ordered by genre slug
 * @param averageRating null until ratings are implemented
 * @param videoStatus   latest video availability, a {@code VideoAssetStatus} value; null when the
 *                      movie has no video asset, which is always the case until video upload is
 *                      implemented
 */
public record MovieSummaryResponse(
        UUID id,
        String title,
        String slug,
        String description,
        Integer releaseYear,
        String posterUrl,
        List<String> genres,
        Double averageRating,
        MovieStatus status,
        String videoStatus
) {

    public MovieSummaryResponse {
        genres = List.copyOf(genres);
    }

    /**
     * The response for a catalog summary, with the fields owned by modules that do not exist yet
     * set to null.
     */
    public static MovieSummaryResponse of(MovieSummary summary) {
        Objects.requireNonNull(summary, "summary");
        return new MovieSummaryResponse(
                summary.id(),
                summary.title(),
                summary.slug(),
                summary.description(),
                summary.releaseYear(),
                null,
                summary.genres(),
                null,
                summary.status(),
                null
        );
    }
}
