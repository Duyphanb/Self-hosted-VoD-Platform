package com.vodplatform.catalog.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * One page of the admin people search ({@code PersonPageResponse} in the API contract).
 */
public record PersonPageResponse(List<PersonResponse> items, PageMeta page) {

    public PersonPageResponse {
        items = List.copyOf(items);
    }

    public static PersonPageResponse of(Page<PersonResponse> page) {
        return new PersonPageResponse(page.getContent(), PageMeta.of(page));
    }
}
