package com.vodplatform.catalog.dto;

import java.util.UUID;

/**
 * A person ({@code PersonResponse} in the API contract).
 */
public record PersonResponse(UUID id, String name, String slug) {
}
