package com.vodplatform.common.support;

import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The database of every Spring context test in this module (issue #119): a test class implements
 * this interface, and its context then runs on one PostgreSQL 16 container that the whole test JVM
 * shares. Boot starts the container with the first context that needs it, Flyway applies the
 * committed migrations ({@code db/migration}, V1 onward) when each context starts, and Hibernate
 * only validates the entity mappings against that schema, as the runtime does.
 *
 * <p>{@link ServiceConnection} gives the contexts the container's JDBC and Flyway connection
 * details, which take precedence over {@code spring.datasource.*} and {@code spring.flyway.*}, so an
 * exported {@code SPRING_DATASOURCE_URL} or {@code SPRING_FLYWAY_URL} cannot point a test at a
 * developer database. {@code PostgresTestDatabaseCoverageTests} fails the build when a
 * {@code @SpringBootTest} class does not implement this interface directly; a subclass declares it
 * again, because JUnit reads the skip condition below only from directly implemented interfaces.
 *
 * <p>All contexts share one database. A test removes the rows it commits (or rolls back with
 * {@code @Transactional}) and uses the {@code ROLE_USER} and {@code ROLE_ADMIN} rows seeded by
 * V2 and V3 instead of inserting or deleting roles.
 *
 * <p>The container needs Docker. On a machine without Docker, {@code mvn verify -DskipPostgresTests}
 * reports these tests as skipped; CI never sets that property.
 */
@DisabledIfSystemProperty(named = PostgresTestDatabase.SKIP_PROPERTY, matches = "true",
        disabledReason = "-DskipPostgresTests: PostgreSQL context tests need Docker")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        // As the runtime Flyway container (FLYWAY_VALIDATE_MIGRATION_NAMING).
        "spring.flyway.validate-migration-naming=true",
        // Spring caches up to 32 contexts, each with its own pool on this one server (100 connections):
        // a pool starts with one connection and grows on demand.
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.minimum-idle=1"
})
public interface PostgresTestDatabase {

    String SKIP_PROPERTY = "skipPostgresTests";

    /**
     * {@code postgres:16.15-alpine}, pinned to the digest of the CI schema-migration service
     * ({@code deploy/tests/test_migration_guards.py} keeps the two equal).
     */
    String IMAGE = "postgres@sha256:0cefaabd882e42bcb2a2f3dc56d3ce4770560ba62fd7f197f1a2e008fd5731ab";

    @ServiceConnection
    PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(IMAGE))
            .withDatabaseName("vod_test")
            // Same cluster collation as Compose and CI (ADR-009 addendum).
            .withEnv("POSTGRES_INITDB_ARGS", "--locale-provider=icu --icu-locale=vi");
}
