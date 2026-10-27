package com.vodplatform.catalog.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * One page of a movie list ({@code MoviePageResponse} in the API contract).
 */
public record MoviePageResponse(List<MovieSummaryResponse> items, PageMeta page) {

    public MoviePageResponse {
        items = List.copyOf(items);
    }

    public static MoviePageResponse of(Page<MovieSummary> page) {
        return new MoviePageResponse(page.map(MovieSummaryResponse::of).getContent(), PageMeta.of(page));
    }
}
