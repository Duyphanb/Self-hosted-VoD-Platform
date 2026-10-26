package com.vodplatform.catalog.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Person persistence for credit assignment and the admin people search (ADR-009).
 */
public interface PersonRepository extends JpaRepository<Person, UUID> {

    /**
     * Admin people list without a search term, ordered by name, then id.
     */
    Page<Person> findAllByOrderByNameAscIdAsc(Pageable pageable);

    /**
     * Admin people search: the slug-normalized term matched as a substring of the person slug,
     * ordered by name, then id.
     */
    Page<Person> findBySlugContainingOrderByNameAscIdAsc(String slugFragment, Pageable pageable);

    /**
     * Name-only credit matching: people whose stored name equals the given normalized name
     * case-insensitively, with diacritics significant. The caller decides what zero, one or
     * several matches mean.
     */
    @Query("select p from Person p where lower(p.name) = lower(:name) order by p.id")
    List<Person> findAllByNameIgnoringCase(@Param("name") String name);

    /**
     * Slugs that may collide with a generated person slug: the base itself and every slug that
     * starts with {@code base + "-"}. The caller picks the first free {@code -2}, {@code -3}, ...
     * suffix. The base must be a slug, so it contains no LIKE wildcard.
     */
    @Query("select p.slug from Person p where p.slug = :base or p.slug like concat(:base, '-%')")
    List<String> findSlugsWithBase(@Param("base") String base);
}
