package com.vodplatform.catalog.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Objects;
import java.util.UUID;

/**
 * Row of {@code people}. Names are not unique; the server generates the unique slug with the
 * ADR-009 slug rule.
 */
@Entity
@Table(
        name = "people",
        uniqueConstraints = @UniqueConstraint(name = CatalogConstraints.PEOPLE_SLUG, columnNames = "slug"),
        indexes = @Index(name = "ix_people_name", columnList = "name")
)
public class Person {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "slug", nullable = false, length = 255)
    private String slug;

    protected Person() {
    }

    public Person(UUID id, String name, String slug) {
        this.id = Objects.requireNonNull(id, "id");
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
