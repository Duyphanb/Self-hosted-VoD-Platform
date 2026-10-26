package com.vodplatform.catalog.dto;

import com.vodplatform.catalog.persistence.CreditRole;
import java.util.UUID;

/**
 * One credit of a {@link MovieDetail} ({@code MovieCredit} in the API contract).
 */
public record CreditedPerson(UUID personId, String name, CreditRole role) {
}
