package com.vodplatform.catalog.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.Length;

@Entity
@Table(name = "episodes", uniqueConstraints = @UniqueConstraint(name = "ux_episodes_title_ordinal",
        columnNames = {"title_id", "ordinal"}))
public class Episode {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "title_id", nullable = false, updatable = false)
    private Movie title;

    @Column(nullable = false)
    private int ordinal;

    @Column(name = "display_label", length = 50)
    private String displayLabel;

    @Column(length = 255)
    private String name;

    @Column(length = Length.LONG32)
    private String synopsis;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MovieStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Episode() {
    }

    Episode(Movie title) {
        this.id = UUID.randomUUID();
        this.title = title;
        this.ordinal = 1;
        this.displayLabel = "1";
        this.status = MovieStatus.PUBLISHED;
        this.createdAt = title.getCreatedAt();
        this.updatedAt = createdAt;
    }

    public UUID getId() { return id; }
    public Movie getTitle() { return title; }
    public int getOrdinal() { return ordinal; }
    public String getDisplayLabel() { return displayLabel == null ? Integer.toString(ordinal) : displayLabel; }
    public String getName() { return name; }
    public String getSynopsis() { return synopsis; }
    public MovieStatus getStatus() { return status; }
}
