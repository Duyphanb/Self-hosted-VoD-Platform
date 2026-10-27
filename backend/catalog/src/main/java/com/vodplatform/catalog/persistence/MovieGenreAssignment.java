package com.vodplatform.catalog.persistence;

import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code movie_genres} with its genre, read for many movies at once by
 * {@link MovieRepository#findGenreAssignments}.
 */
public record MovieGenreAssignment(UUID movieId, Genre genre) {

    public MovieGenreAssignment {
        Objects.requireNonNull(movieId, "movieId");
        Objects.requireNonNull(genre, "genre");
    }
}
