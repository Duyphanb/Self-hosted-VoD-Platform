package com.vodplatform.catalog.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Person persistence for credit assignment and the admin people search (ADR-009).
 *
 * <p>Generated slugs can collide with a slug another transaction writes concurrently. Both writes
 * here report such a collision as a result instead of a failed statement, because on PostgreSQL a
 * failed statement aborts the whole transaction and the caller could not try the next suffix.
 */
public interface PersonRepository extends JpaRepository<Person, UUID>, PersonRenaming {

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

    /**
     * Inserts a person unless another person already has the slug, and returns the number of rows
     * inserted: 1, or 0 when the slug is taken and the caller should pick the next suffix.
     *
     * <p>{@code ON CONFLICT DO NOTHING} skips a duplicate instead of raising a unique violation that
     * would abort the transaction on PostgreSQL. When the conflicting row is not committed yet,
     * PostgreSQL waits for that transaction: after a commit the insert does nothing and the next
     * slug lookup (READ COMMITTED) sees the taken slug; after a rollback the insert proceeds. The
     * id is new, so {@code ux_people_slug} is the only constraint that can conflict.
     *
     * <p>H2 accepts this statement only in PostgreSQL compatibility mode, which the catalog and
     * application tests enable.
     */
    @Modifying
    @Query(
            value = "insert into people (id, name, slug) values (:id, :name, :slug) on conflict do nothing",
            nativeQuery = true
    )
    int insertIfSlugFree(@Param("id") UUID id, @Param("name") String name, @Param("slug") String slug);
}
