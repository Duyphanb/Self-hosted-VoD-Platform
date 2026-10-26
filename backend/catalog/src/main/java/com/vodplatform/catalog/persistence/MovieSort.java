package com.vodplatform.catalog.persistence;

import org.springframework.data.domain.Sort;

/**
 * Movie list orders from the API contract ({@code MovieSort}). Each order ends with the movie id
 * so that pages stay stable when the leading key ties.
 */
public enum MovieSort {

    /**
     * Contract value {@code latest}: creation time descending, then id descending.
     */
    LATEST(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))),

    /**
     * Contract value {@code title}: lower-cased title ascending, then id ascending.
     */
    TITLE(Sort.by(Sort.Order.asc("title").ignoreCase(), Sort.Order.asc("id")));

    private final Sort sort;

    MovieSort(Sort sort) {
        this.sort = sort;
    }

    public Sort sort() {
        return sort;
    }
}
