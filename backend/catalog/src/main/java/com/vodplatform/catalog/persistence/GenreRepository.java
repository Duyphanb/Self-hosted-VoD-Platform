package com.vodplatform.catalog.persistence;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Genre persistence. Genres are matched by slug, the ADR-009 match key.
 */
public interface GenreRepository extends JpaRepository<Genre, UUID> {

    /**
     * Loads a genre for rename with a {@code PESSIMISTIC_WRITE} row lock, held until the
     * transaction ends ({@code FOR NO KEY UPDATE} on PostgreSQL, {@code FOR UPDATE} on H2). A
     * rename that waits for the lock reads the row as the previous rename committed it, so the
     * new name is compared with, and written over, the genre's current name and slug, and
     * concurrent renames of the same genre run one after another. On PostgreSQL the lock does not
     * conflict with the key-share lock that a movie write takes to assign the genre; only the
     * update that changes the uniquely indexed name or slug does. Call it before anything else
     * loads the genre in the transaction: an instance already in the persistence context is
     * returned without its state being reread.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Genre> findForUpdateById(UUID id);

    Optional<Genre> findBySlug(String slug);

    /**
     * Resolves the genres of one movie request in a single query.
     */
    List<Genre> findAllBySlugIn(Collection<String> slugs);

    /**
     * Admin genre list: every genre ordered by name, then id.
     */
    List<Genre> findAllByOrderByNameAscIdAsc();

    /**
     * Rename pre-check: whether another genre already uses the slug.
     */
    boolean existsBySlugAndIdNot(String slug, UUID id);

    /**
     * Inserts a genre unless another genre already has its name or slug, and returns the number
     * of rows inserted: 1, or 0 when the genre exists.
     *
     * <p>A plain insert of a duplicate would raise a unique violation, and on PostgreSQL a failed
     * statement aborts the whole transaction, so the caller could no longer re-read the genre
     * the other request created. {@code ON CONFLICT DO NOTHING} skips the row instead and keeps
     * the transaction usable. When the conflicting row is not committed yet, PostgreSQL waits for
     * that transaction: after a commit the insert does nothing, and a read in the next statement
     * (READ COMMITTED) sees the committed genre; after a rollback the insert proceeds. Without a
     * conflict target every unique constraint arbitrates, so {@code ux_genres_name} is covered as
     * well as {@code ux_genres_slug}.
     *
     * <p>H2 accepts this statement only in PostgreSQL compatibility mode, which the catalog and
     * application tests enable.
     */
    @Modifying
    @Query(
            value = "insert into genres (id, name, slug) values (:id, :name, :slug) on conflict do nothing",
            nativeQuery = true
    )
    int insertIfAbsent(@Param("id") UUID id, @Param("name") String name, @Param("slug") String slug);
}
