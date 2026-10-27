package com.vodplatform.catalog.web;

import com.vodplatform.catalog.service.CatalogPaging;
import com.vodplatform.catalog.service.MovieService;

/**
 * Validation messages of the catalog query parameters that the controllers check with
 * {@code @Min} and {@code @Max}, matching the ranges of the API contract.
 */
final class CatalogParameters {

    static final String PAGE_RANGE = "must be between 0 and " + CatalogPaging.MAX_PAGE;
    static final String SIZE_RANGE = "must be between 1 and " + CatalogPaging.MAX_PAGE_SIZE;
    static final String YEAR_RANGE =
            "must be between " + MovieService.MIN_RELEASE_YEAR + " and " + MovieService.MAX_RELEASE_YEAR;

    private CatalogParameters() {
    }
}
