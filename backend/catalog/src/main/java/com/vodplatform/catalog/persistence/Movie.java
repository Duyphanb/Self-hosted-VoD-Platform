package com.vodplatform.catalog.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.hibernate.Length;

/**
 * Row of {@code movies}, the catalog aggregate root. Genre assignments ({@code movie_genres}) and
 * credits ({@code movie_credits}) are managed through this entity.
 *
 * <p>{@code search_vector} is intentionally not mapped: search (Issue 7.2) owns it, and Hibernate
 * schema validation ignores unmapped columns.
 */
@Entity
@Table(
        name = "movies",
        uniqueConstraints = @UniqueConstraint(name = CatalogConstraints.MOVIES_SLUG, columnNames = "slug"),
        indexes = {
                @Index(name = "ix_movies_status", columnList = "status"),
                @Index(name = "ix_movies_release_year", columnList = "release_year")
        }
)
public class Movie {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "slug", nullable = false, length = 255)
    private String slug;

    // V1 declares text. Length.LONG32 maps to text on PostgreSQL; the API contract caps the input.
    @Column(name = "description", nullable = false, length = Length.LONG32)
    private String description;

    @Column(name = "release_year")
    private Integer releaseYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "maturity_rating", length = 20)
    private MaturityRating maturityRating;

    @Column(name = "poster_object_key", length = 512)
    private String posterObjectKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MovieStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "movie_genres",
            joinColumns = @JoinColumn(name = "movie_id", nullable = false),
            inverseJoinColumns = @JoinColumn(name = "genre_id", nullable = false)
    )
    private Set<Genre> genres = new LinkedHashSet<>();

    @OneToMany(mappedBy = "movie", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<MovieCredit> credits = new ArrayList<>();

    protected Movie() {
    }

    public Movie(
            UUID id,
            String title,
            String slug,
            String description,
            Integer releaseYear,
            MaturityRating maturityRating,
            MovieStatus status,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.title = Objects.requireNonNull(title, "title");
        this.slug = Objects.requireNonNull(slug, "slug");
        this.description = Objects.requireNonNull(description, "description");
        this.releaseYear = releaseYear;
        this.maturityRating = maturityRating;
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = createdAt;
    }

    /**
     * Assigns a genre; assigning the same genre twice keeps one assignment.
     */
    public void addGenre(Genre genre) {
        Objects.requireNonNull(genre, "genre");
        // Genre has no value equality, so deduplicate by id: a detached or reloaded instance of an
        // assigned genre must not add a second movie_genres row.
        boolean assigned = genres.stream().anyMatch(existing -> existing.getId().equals(genre.getId()));
        if (!assigned) {
            genres.add(genre);
        }
    }

    /**
     * Credits a person in a role. Each (person, role) pair may appear once per movie, matching
     * the {@code movie_credits} primary key.
     *
     * @throws IllegalArgumentException if the person already has this role in the movie
     */
    public void addCredit(Person person, CreditRole role, Integer sortOrder) {
        Objects.requireNonNull(person, "person");
        Objects.requireNonNull(role, "role");
        boolean duplicate = credits.stream()
                .anyMatch(credit -> credit.getPerson().getId().equals(person.getId()) && credit.getRole() == role);
        if (duplicate) {
            throw new IllegalArgumentException("The person already has this credit role in the movie");
        }
        credits.add(new MovieCredit(this, person, role, sortOrder));
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getSlug() {
        return slug;
    }

    public String getDescription() {
        return description;
    }

    public Integer getReleaseYear() {
        return releaseYear;
    }

    public MaturityRating getMaturityRating() {
        return maturityRating;
    }

    public String getPosterObjectKey() {
        return posterObjectKey;
    }

    public MovieStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Set<Genre> getGenres() {
        return Collections.unmodifiableSet(genres);
    }

    /**
     * Returns the credits in display order ({@code sort_order}).
     */
    public List<MovieCredit> getCredits() {
        return Collections.unmodifiableList(credits);
    }
}
