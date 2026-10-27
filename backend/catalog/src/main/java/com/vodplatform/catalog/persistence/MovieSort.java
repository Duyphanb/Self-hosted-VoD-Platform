package com.vodplatform.catalog.persistence;

import java.util.Arrays;
import java.util.Optional;
import org.springframework.data.domain.Sort;

/**
 * Movie list orders from the API contract ({@code MovieSort}). Each order ends with the movie id
 * so that pages stay stable when the leading key ties.
 */
public enum MovieSort {

    /**
     * Contract value {@code latest}: creation time descending, then id descending.
     */
    LATEST("latest", Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))),

    /**
     * Contract value {@code title}: lower-cased title ascending, then id ascending.
     */
    TITLE("title", Sort.by(Sort.Order.asc("title").ignoreCase(), Sort.Order.asc("id")));

    private final String contractValue;
    private final Sort sort;

    MovieSort(String contractValue, Sort sort) {
        this.contractValue = contractValue;
        this.sort = sort;
    }

    /**
     * Returns the order with the given contract value. The match is exact: the contract declares
     * the values in lower case, and any other spelling is not a {@code MovieSort}.
     */
    public static Optional<MovieSort> fromContractValue(String value) {
        return Arrays.stream(values())
                .filter(order -> order.contractValue.equals(value))
                .findFirst();
    }

    /** The value of this order in the API contract's {@code sort} parameter. */
    public String contractValue() {
        return contractValue;
    }

    public Sort sort() {
        return sort;
    }
}
