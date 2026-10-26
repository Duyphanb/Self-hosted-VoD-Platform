/**
 * Catalog persistence: JPA entities for {@code movies}, {@code genres}, {@code movie_genres},
 * {@code people} and {@code movie_credits}, and their Spring Data repositories.
 *
 * <p>The mapping follows the Flyway V1 schema exactly, including the {@code ux_*} unique constraint
 * names; Flyway owns the schema and the runtime only validates it. These types are internal to the
 * catalog module: API responses use DTOs, and other modules use the catalog services rather than
 * these entities.
 */
package com.vodplatform.catalog.persistence;
