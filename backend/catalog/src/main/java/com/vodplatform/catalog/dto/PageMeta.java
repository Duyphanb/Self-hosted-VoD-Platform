package com.vodplatform.catalog.dto;

import org.springframework.data.domain.Page;

/**
 * Paging information of a list response ({@code PageMeta} in the API contract).
 *
 * @param page          zero-based page index
 * @param size          requested page size
 * @param totalElements number of items on all pages
 * @param totalPages    number of pages
 */
public record PageMeta(int page, int size, long totalElements, int totalPages) {

    public static PageMeta of(Page<?> page) {
        return new PageMeta(page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
