package com.vodplatform.catalog.dto;

import com.vodplatform.catalog.persistence.MovieStatus;
import com.vodplatform.catalog.persistence.ContentType;
import com.vodplatform.catalog.service.VideoStatusLookup.Availability;
import java.util.List;
import java.util.UUID;

/**
 * Catalog fields of a movie list item ({@code MovieSummary} in the API contract). Fields owned by
 * other modules, such as the poster URL and average rating, are added by the API
 * layer ({@link MovieSummaryResponse}).
 *
 * @param genres genre names ordered by genre slug
 * @param availability Visible episode counts and title availability
 */
public record MovieSummary(
        UUID id,
        String title,
        String slug,
        String description,
        Integer releaseYear,
        List<String> genres,
        MovieStatus status,
        ContentType contentType,
        String partLabel,
        Availability availability
) {

    public MovieSummary {
        genres = List.copyOf(genres);
    }

}
