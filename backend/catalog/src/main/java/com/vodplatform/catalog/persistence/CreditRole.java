package com.vodplatform.catalog.persistence;

/**
 * Role of a person in a movie; the values match the {@code movie_credits.credit_role} CHECK
 * constraint in V1.
 */
public enum CreditRole {
    ACTOR,
    DIRECTOR
}
