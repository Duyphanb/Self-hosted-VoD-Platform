package com.vodplatform.catalog.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * Page parameter limits of the API contract ({@code Page} and {@code Size} parameters), shared by
 * the catalog lists. The API layer validates the parameters first; the services reject values
 * outside the range as a programming error.
 */
public final class CatalogPaging {

    /** Highest page index the API contract accepts. */
    public static final int MAX_PAGE = 10_000;

    /** Largest page size the API contract accepts. */
    public static final int MAX_PAGE_SIZE = 100;

    /** Page size when the request has none. */
    public static final int DEFAULT_PAGE_SIZE = 20;

    private CatalogPaging() {
    }

    /**
     * @throws IllegalArgumentException if the page or size is outside the contract range
     */
    static PageRequest pageRequest(int page, int size, Sort sort) {
        if (page < 0 || page > MAX_PAGE) {
            throw new IllegalArgumentException("Page index must be between 0 and " + MAX_PAGE);
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Page size must be between 1 and " + MAX_PAGE_SIZE);
        }
        return PageRequest.of(page, size, sort);
    }
}
