package com.vodplatform.catalog.dto;

/**
 * Admin person payload ({@code UpsertPersonRequest} in the API contract) for create and rename. The
 * value is kept as received; {@code PersonService} validates and normalizes it and reports problems
 * as 400 {@code VALIDATION_ERROR} field errors.
 */
public record UpsertPersonRequest(String name) {
}
