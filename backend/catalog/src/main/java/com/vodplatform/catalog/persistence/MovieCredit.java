package com.vodplatform.catalog.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

/**
 * Row of {@code movie_credits}, keyed by (movie, person, credit role). Created through
 * {@link Movie#addCredit(Person, CreditRole, Integer)}.
 */
@Entity
@Table(
        name = "movie_credits",
        indexes = {
                @Index(name = "ix_movie_credits_person_id", columnList = "person_id"),
                @Index(name = "ix_movie_credits_role", columnList = "credit_role")
        }
)
public class MovieCredit {

    @EmbeddedId
    private MovieCreditId id;

    @MapsId("movieId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "movie_id", nullable = false)
    private Movie movie;

    @MapsId("personId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "person_id", nullable = false)
    private Person person;

    @Column(name = "sort_order")
    private Integer sortOrder;

    protected MovieCredit() {
    }

    MovieCredit(Movie movie, Person person, CreditRole role, Integer sortOrder) {
        this.id = new MovieCreditId(movie.getId(), person.getId(), role);
        this.movie = movie;
        this.person = person;
        this.sortOrder = sortOrder;
    }

    public Person getPerson() {
        return person;
    }

    public CreditRole getRole() {
        return id.getCreditRole();
    }

    public Integer getSortOrder() {
        return sortOrder;
    }
}
