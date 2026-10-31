package com.vodplatform.catalog.service;

import com.vodplatform.catalog.dto.CreditedPerson;
import com.vodplatform.catalog.dto.MovieDetail;
import com.vodplatform.catalog.dto.MovieSummary;
import com.vodplatform.catalog.persistence.Genre;
import com.vodplatform.catalog.persistence.Movie;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Maps movie entities to the catalog DTOs. Call it inside the transaction that loaded the movie,
 * because credits load lazily.
 *
 * <p>A summary takes its genres as an argument instead of reading the movie's lazy collection,
 * so a list of movies is mapped from genres read for the whole page with one query
 * ({@link MovieService}) rather than one query per movie.
 */
final class MovieMapper {

    private MovieMapper() {
    }

    /**
     * @param genres the genres assigned to the movie, in any order
     */
    static MovieSummary toSummary(Movie movie, Collection<Genre> genres, String videoStatus) {
        return new MovieSummary(
                movie.getId(),
                movie.getTitle(),
                movie.getSlug(),
                movie.getDescription(),
                movie.getReleaseYear(),
                genreNames(genres),
                movie.getStatus(),
                videoStatus
        );
    }

    static MovieDetail toDetail(Movie movie, String videoStatus) {
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
                genreNames(movie.getGenres()),
                people,
                movie.getStatus(),
                videoStatus
        );
    }

    /**
     * {@code movie_genres} has no order column, so names are ordered by slug for a stable result.
     */
    private static List<String> genreNames(Collection<Genre> genres) {
        return genres.stream()
                .sorted(Comparator.comparing(Genre::getSlug))
                .map(Genre::getName)
                .toList();
    }
}
