package com.vodplatform.catalog.dto;

import com.vodplatform.catalog.persistence.CreditRole;
import java.util.UUID;

/**
 * One credit of an {@link UpsertMovieRequest} ({@code MovieCreditInput} in the API contract).
 * Either {@code personId} or {@code name} is required; when {@code personId} is present it
 * identifies the person and {@code name} is ignored.
 */
public record MovieCreditInput(UUID personId, String name, CreditRole role) {
}
