package com.vodplatform.catalog.persistence;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Optional movie list filters combined by {@link MovieRepository#findPublished}.
 */
final class MovieSpecifications {

    private MovieSpecifications() {
    }

    static Specification<Movie> hasStatus(MovieStatus status) {
        return (root, query, builder) -> builder.equal(root.get("status"), status);
    }

    static Specification<Movie> releasedIn(int releaseYear) {
        return (root, query, builder) -> builder.equal(root.get("releaseYear"), releaseYear);
    }

    /**
     * Matches movies assigned to the genre with the given slug. An EXISTS subquery keeps one row
     * per movie, so page counts are not affected by the join.
     */
    static Specification<Movie> inGenre(String genreSlug) {
        return (root, query, builder) -> {
            Subquery<UUID> genreIds = query.subquery(UUID.class);
            Root<Movie> movie = genreIds.correlate(root);
            Join<Movie, Genre> genre = movie.join("genres");
            genreIds.select(genre.get("id")).where(builder.equal(genre.get("slug"), genreSlug));
            return builder.exists(genreIds);
        };
    }
}
