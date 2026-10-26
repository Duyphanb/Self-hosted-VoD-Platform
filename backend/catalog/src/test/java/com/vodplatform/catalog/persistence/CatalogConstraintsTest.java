package com.vodplatform.catalog.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class CatalogConstraintsTest {

    @Test
    void matchesTheBareNameReportedByPostgresql() {
        Throwable failure = wrapped(violation("ux_movies_slug"));

        assertThat(CatalogConstraints.isViolationOf(failure, CatalogConstraints.MOVIES_SLUG)).isTrue();
        assertThat(CatalogConstraints.isViolationOf(failure, CatalogConstraints.GENRES_SLUG)).isFalse();
    }

    @Test
    void matchesTheQualifiedNameAndIndexReportedByH2() {
        Throwable failure = wrapped(violation("PUBLIC.UX_GENRES_SLUG INDEX PUBLIC.UX_GENRES_SLUG_INDEX_7"));

        assertThat(CatalogConstraints.isViolationOf(failure, CatalogConstraints.GENRES_SLUG)).isTrue();
        assertThat(CatalogConstraints.isViolationOf(failure, CatalogConstraints.GENRES_NAME)).isFalse();
    }

    @Test
    void matchesAQuotedSchemaQualifiedName() {
        assertThat(CatalogConstraints.isViolationOf(violation("\"public\".\"ux_people_slug\""), CatalogConstraints.PEOPLE_SLUG))
                .isTrue();
    }

    @Test
    void doesNotMatchAPrefixOrAnUnknownConstraint() {
        assertThat(CatalogConstraints.isViolationOf(violation("ux_movies_slug_old"), CatalogConstraints.MOVIES_SLUG)).isFalse();
        assertThat(CatalogConstraints.isViolationOf(violation(null), CatalogConstraints.MOVIES_SLUG)).isFalse();
        assertThat(CatalogConstraints.isViolationOf(new IllegalStateException("ux_movies_slug"), CatalogConstraints.MOVIES_SLUG))
                .isFalse();
        assertThat(CatalogConstraints.isViolationOf(null, CatalogConstraints.MOVIES_SLUG)).isFalse();
    }

    private static ConstraintViolationException violation(String constraintName) {
        return new ConstraintViolationException("could not execute statement", new SQLException("duplicate"), constraintName);
    }

    private static Throwable wrapped(Throwable cause) {
        return new DataIntegrityViolationException("could not execute statement", cause);
    }
}
