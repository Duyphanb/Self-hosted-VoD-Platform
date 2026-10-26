package com.vodplatform.catalog.service;

import com.vodplatform.catalog.dto.CreditedPerson;
import com.vodplatform.catalog.dto.MovieDetail;
import com.vodplatform.catalog.dto.MovieSummary;
import com.vodplatform.catalog.persistence.Genre;
import com.vodplatform.catalog.persistence.Movie;
import java.util.Comparator;
import java.util.List;

/**
 * Maps movie entities to the catalog DTOs. Call it inside the transaction that loaded the movie,
 * because genres and credits load lazily.
 */
final class MovieMapper {

    private MovieMapper() {
    }

    static MovieSummary toSummary(Movie movie) {
        return new MovieSummary(
                movie.getId(),
                movie.getTitle(),
                movie.getSlug(),
                movie.getDescription(),
                movie.getReleaseYear(),
                genreNames(movie),
                movie.getStatus()
        );
    }

    static MovieDetail toDetail(Movie movie) {
        List<CreditedPerson> people = movie.getCredits().stream()
                .map(credit -> new CreditedPerson(
                        credit.getPerson().getId(),
                        credit.getPerson().getName(),
                        credit.getRole()
                ))
                .toList();
        return new MovieDetail(
                movie.getId(),
                movie.getTitle(),
                movie.getSlug(),
                movie.getDescription(),
                movie.getReleaseYear(),
                movie.getMaturityRating(),
                genreNames(movie),
                people,
                movie.getStatus()
        );
    }

    /**
     * {@code movie_genres} has no order column, so names are ordered by slug for a stable result.
     */
    private static List<String> genreNames(Movie movie) {
        return movie.getGenres().stream()
                .sorted(Comparator.comparing(Genre::getSlug))
                .map(Genre::getName)
                .toList();
    }
}
