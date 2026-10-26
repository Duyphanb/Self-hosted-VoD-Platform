package com.vodplatform.catalog.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Genre persistence. Genres are matched by slug, the ADR-009 match key.
 */
public interface GenreRepository extends JpaRepository<Genre, UUID> {

    Optional<Genre> findBySlug(String slug);

    /**
     * Resolves the genres of one movie request in a single query.
     */
    List<Genre> findAllBySlugIn(Collection<String> slugs);

    /**
     * Admin genre list: every genre ordered by name, then id.
     */
    List<Genre> findAllByOrderByNameAscIdAsc();
}
