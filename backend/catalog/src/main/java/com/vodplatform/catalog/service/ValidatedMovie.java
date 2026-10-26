package com.vodplatform.catalog.service;

import com.vodplatform.catalog.persistence.CreditRole;
import com.vodplatform.catalog.persistence.MaturityRating;
import com.vodplatform.catalog.persistence.MovieStatus;
import java.util.List;
import java.util.UUID;

/**
 * An {@code UpsertMovieRequest} that passed {@link MovieRequestValidator}: every value is within
 * the contract limits and text values are normalized.
 */
record ValidatedMovie(
        String title,
        String slug,
        String description,
        Integer releaseYear,
        MaturityRating maturityRating,
        MovieStatus status,
        List<GenreInput> genres,
        List<CreditInput> people
) {

    ValidatedMovie {
        genres = List.copyOf(genres);
        people = List.copyOf(people);
    }

    /**
     * A requested genre: the normalized name and its non-empty slug, unique within the request.
     */
    record GenreInput(String name, String slug) {
    }

    /**
     * A requested credit. Exactly one of {@code personId} and {@code name} is set; {@code name} is
     * normalized. {@code index} is the position in the request, used for field paths and as the
     * display order.
     */
    record CreditInput(int index, UUID personId, String name, CreditRole role) {

        String field() {
            return "people[" + index + "]";
        }
    }
}
