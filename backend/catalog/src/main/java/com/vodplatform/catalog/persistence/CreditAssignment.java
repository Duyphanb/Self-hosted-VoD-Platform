package com.vodplatform.catalog.persistence;

import java.util.Objects;

/**
 * One entry of a movie's credit list for {@link Movie#replaceCredits(java.util.List)}: a person in
 * a role. The position in the list is the display order.
 */
public record CreditAssignment(Person person, CreditRole role) {

    public CreditAssignment {
        Objects.requireNonNull(person, "person");
        Objects.requireNonNull(role, "role");
    }
}
