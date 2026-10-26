package com.vodplatform.catalog.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite primary key of {@code movie_credits}: (movie_id, person_id, credit_role).
 */
@Embeddable
class MovieCreditId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "movie_id", nullable = false)
    private UUID movieId;

    @Column(name = "person_id", nullable = false)
    private UUID personId;

    @Enumerated(EnumType.STRING)
    @Column(name = "credit_role", nullable = false, length = 20)
    private CreditRole creditRole;

    protected MovieCreditId() {
    }

    MovieCreditId(UUID movieId, UUID personId, CreditRole creditRole) {
        this.movieId = Objects.requireNonNull(movieId, "movieId");
        this.personId = Objects.requireNonNull(personId, "personId");
        this.creditRole = Objects.requireNonNull(creditRole, "creditRole");
    }

    CreditRole getCreditRole() {
        return creditRole;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MovieCreditId that)) {
            return false;
        }
        return Objects.equals(movieId, that.movieId)
                && Objects.equals(personId, that.personId)
                && creditRole == that.creditRole;
    }

    @Override
    public int hashCode() {
        return Objects.hash(movieId, personId, creditRole);
    }
}
