package com.vodplatform.catalog.persistence;

/**
 * Movie lifecycle status; the values match the {@code titles.status} CHECK constraint.
 */
public enum MovieStatus {
    DRAFT,
    PUBLISHED,
    ARCHIVED
}
