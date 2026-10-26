package com.vodplatform.catalog.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Movie persistence. Callers pass a {@link MovieSort} order in the {@link Pageable}. Routes use
 * the movie id (ADR-009), so slugs are only checked for uniqueness, never used to load a movie.
 */
public interface MovieRepository extends JpaRepository<Movie, UUID>, JpaSpecificationExecutor<Movie> {

    /**
     * Slug pre-check on create. The {@code ux_movies_slug} constraint remains the final guard.
     */
    boolean existsBySlug(String slug);

    /**
     * Slug pre-check on update: whether another movie already uses the slug.
     */
    boolean existsBySlugAndIdNot(String slug, UUID id);

    /**
     * Admin list filtered by status; {@link #findAll(Pageable)} lists every status.
     */
    Page<Movie> findByStatus(MovieStatus status, Pageable pageable);

    /**
     * User-facing list: {@code PUBLISHED} movies only, for every role.
     *
     * @param genreSlug genre slug to filter by, or null for every genre; callers normalize the
     *                  query value with the slug rule first
     * @param releaseYear release year to filter by, or null for every year
     */
    default Page<Movie> findPublished(String genreSlug, Integer releaseYear, Pageable pageable) {
        List<Specification<Movie>> filters = new ArrayList<>();
        filters.add(MovieSpecifications.hasStatus(MovieStatus.PUBLISHED));
        if (genreSlug != null) {
            filters.add(MovieSpecifications.inGenre(genreSlug));
        }
        if (releaseYear != null) {
            filters.add(MovieSpecifications.releasedIn(releaseYear));
        }
        return findAll(Specification.allOf(filters), pageable);
    }
}
