package com.vodplatform.catalog.dto;

import com.vodplatform.catalog.persistence.MaturityRating;
import com.vodplatform.catalog.persistence.MovieStatus;
import java.util.List;
import java.util.UUID;

/**
 * Catalog fields of a movie detail ({@code MovieDetail} in the API contract). Fields owned by
 * other modules, such as the poster URL, average rating, the caller's rating, watchlist state,
 * playback progress and video status, are added by the API layer ({@link MovieDetailResponse}).
 *
 * @param genres genre names ordered by genre slug
 * @param people credits in display order ({@code sort_order})
 */
public record MovieDetail(
        UUID id,
        String title,
        String slug,
        String description,
        Integer releaseYear,
        MaturityRating maturityRating,
        List<String> genres,
        List<CreditedPerson> people,
        MovieStatus status
) {

    public MovieDetail {
        genres = List.copyOf(genres);
        people = List.copyOf(people);
    }
}
