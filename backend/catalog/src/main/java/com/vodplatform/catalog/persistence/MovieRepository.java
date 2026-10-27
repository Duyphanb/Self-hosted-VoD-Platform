package com.vodplatform.catalog.persistence;

import jakarta.persistence.LockModeType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Movie persistence. Callers pass a {@link MovieSort} order in the {@link Pageable}. Routes use
 * the movie id (ADR-009), so slugs are only checked for uniqueness, never used to load a movie.
 */
public interface MovieRepository extends JpaRepository<Movie, UUID>, JpaSpecificationExecutor<Movie> {

    /**
     * Loads a movie for update or archive with a {@code PESSIMISTIC_WRITE} row lock, held until the
     * transaction ends. Hibernate renders it as {@code FOR NO KEY UPDATE} on PostgreSQL, which
     * conflicts with another writer's lock but not with the key-share locks taken when rows that
     * reference the movie are inserted, and as {@code FOR UPDATE} on H2. Concurrent writes to the
     * same movie run one after another, each on the state the previous one committed, so the last
     * write wins and replacing genres and credits never races. Call it before anything else loads
     * the movie in the transaction: an instance already in the persistence context is returned
     * without its state being reread.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Movie> findForUpdateById(UUID id);

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
     * The genre assignments of the given movies, read with one query, so a page of movies is mapped
     * without loading each movie's genres separately. A movie without genres has no row; the order
     * of the rows is unspecified.
     *
     * @param movieIds the movies to read; must not be empty
     */
    @Query("select new com.vodplatform.catalog.persistence.MovieGenreAssignment(m.id, g)"
            + " from Movie m join m.genres g where m.id in :movieIds")
    List<MovieGenreAssignment> findGenreAssignments(@Param("movieIds") Collection<UUID> movieIds);

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
