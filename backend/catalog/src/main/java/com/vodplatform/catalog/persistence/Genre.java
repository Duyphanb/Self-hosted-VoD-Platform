package com.vodplatform.catalog.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Objects;
import java.util.UUID;

/**
 * Row of {@code genres}. The slug is derived from the name with the ADR-009 slug rule and is the
 * genre match key.
 */
@Entity
@Table(
        name = "genres",
        uniqueConstraints = {
                @UniqueConstraint(name = CatalogConstraints.GENRES_NAME, columnNames = "name"),
                @UniqueConstraint(name = CatalogConstraints.GENRES_SLUG, columnNames = "slug")
        }
)
public class Genre {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "slug", nullable = false, length = 120)
    private String slug;

    protected Genre() {
    }

    public Genre(UUID id, String name, String slug) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.slug = Objects.requireNonNull(slug, "slug");
    }

    /**
     * Renames the genre. The caller derives the slug from the new name with the slug rule.
     */
    public void rename(String name, String slug) {
        this.name = Objects.requireNonNull(name, "name");
        this.slug = Objects.requireNonNull(slug, "slug");
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }
}
