package com.vodplatform.catalog.persistence;

/**
 * Movie lifecycle status; the values match the {@code movies.status} CHECK constraint in V1.
 */
public enum MovieStatus {
    DRAFT,
    PUBLISHED,
    ARCHIVED
}
