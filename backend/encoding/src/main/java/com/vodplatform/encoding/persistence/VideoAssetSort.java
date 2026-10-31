package com.vodplatform.encoding.persistence;

import org.springframework.data.domain.Sort;

/** Stable asset paging order; database UUID ordering is authoritative. */
public final class VideoAssetSort {
    public static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private VideoAssetSort() {
    }
}
