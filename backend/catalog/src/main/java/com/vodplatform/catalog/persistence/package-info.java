/**
 * Catalog persistence: JPA entities for {@code titles}, {@code genres}, {@code title_genres},
 * {@code people} and {@code title_credits}, and their Spring Data repositories.
 *
 * <p>The mapping follows the migrated Flyway schema, including the {@code ux_*} unique constraint
 * names; Flyway owns the schema and the runtime only validates it. The entities and repositories are
 * internal to the catalog module: API responses use DTOs, and other modules use the catalog services
 * rather than these entities. The enums ({@link MovieStatus}, {@link MaturityRating},
 * {@link CreditRole}, {@link MovieSort}) are plain values that the catalog DTOs and services share.
 */
package com.vodplatform.catalog.persistence;
