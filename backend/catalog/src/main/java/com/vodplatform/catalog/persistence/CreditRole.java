package com.vodplatform.catalog.persistence;

/**
 * Role of a person in a movie; the values match the {@code title_credits.credit_role} CHECK constraint.
 */
public enum CreditRole {
    ACTOR,
    DIRECTOR
}
