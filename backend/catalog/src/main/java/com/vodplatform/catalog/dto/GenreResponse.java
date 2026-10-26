package com.vodplatform.catalog.dto;

import java.util.UUID;

/**
 * A genre ({@code GenreResponse} in the API contract).
 */
public record GenreResponse(UUID id, String name, String slug) {
}
