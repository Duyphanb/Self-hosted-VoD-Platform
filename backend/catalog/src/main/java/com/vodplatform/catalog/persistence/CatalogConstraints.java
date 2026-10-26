package com.vodplatform.catalog.persistence;

import java.util.Locale;
import org.hibernate.exception.ConstraintViolationException;

/**
 * Names of the V1 unique constraints on catalog tables, declared on the entities so that the H2
 * test schema uses the same names as the Flyway schema, and a check that maps a failed write to
 * one of them.
 */
public final class CatalogConstraints {

    public static final String MOVIES_SLUG = "ux_movies_slug";
    public static final String GENRES_NAME = "ux_genres_name";
    public static final String GENRES_SLUG = "ux_genres_slug";
    public static final String PEOPLE_SLUG = "ux_people_slug";

    private CatalogConstraints() {
    }

    /**
     * Returns whether the failure, or one of its causes, is a Hibernate constraint violation of the
     * named constraint.
     *
     * <p>PostgreSQL reports the bare name ({@code ux_movies_slug}); H2 reports it schema-qualified
     * and followed by its backing index ({@code PUBLIC.UX_MOVIES_SLUG INDEX
     * PUBLIC.UX_MOVIES_SLUG_INDEX_8}). The reported name is reduced to its first token without a
     * schema qualifier and compared case-insensitively, so both databases match the same name.
     */
    public static boolean isViolationOf(Throwable failure, String constraintName) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ConstraintViolationException violation
                    && constraintName.equalsIgnoreCase(reportedName(violation.getConstraintName()))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String reportedName(String constraintName) {
        if (constraintName == null) {
            return null;
        }
        String name = constraintName.strip();
        int whitespace = indexOfWhitespace(name);
        if (whitespace >= 0) {
            name = name.substring(0, whitespace);
        }
        name = name.substring(name.lastIndexOf('.') + 1);
        return name.replace("\"", "").toLowerCase(Locale.ROOT);
    }

    private static int indexOfWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                return index;
            }
        }
        return -1;
    }
}
