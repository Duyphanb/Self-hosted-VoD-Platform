package com.vodplatform.catalog.persistence;

/**
 * Vietnamese film classification accepted by the API contract ({@code MaturityRating}). The
 * {@code movies.maturity_rating} column has no CHECK constraint; null means not classified.
 */
public enum MaturityRating {
    P,
    K,
    T13,
    T16,
    T18
}
