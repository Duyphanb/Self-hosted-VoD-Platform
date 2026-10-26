package com.vodplatform.catalog.persistence;

import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.Session;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

/**
 * Renames a person inside a JDBC savepoint, the one catalog write that cannot use
 * {@code ON CONFLICT DO NOTHING}: PostgreSQL has no such clause for {@code UPDATE}.
 *
 * <p>When another transaction writes the same slug concurrently, the update fails on
 * {@code ux_people_slug}. On PostgreSQL that failure aborts the transaction unless it is rolled
 * back to a savepoint first, so the statement runs on the transaction's connection between a
 * savepoint and its release, and a unique violation ({@code SQLSTATE 23505}) rolls back to the
 * savepoint and reports {@code false}. It runs as plain JDBC rather than as a Hibernate query,
 * because Hibernate marks the transaction rollback-only when a statement it executes fails, which
 * a savepoint cannot undo. Hibernate therefore never sees the statement, and the person is
 * refreshed afterwards so that the persistence context matches the row.
 */
class PersonRenamingImpl implements PersonRenaming {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String RENAME = "update people set name = ?, slug = ? where id = ?";

    private final EntityManager entityManager;

    PersonRenamingImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public boolean renameIfSlugFree(Person person, String name, String slug) {
        Objects.requireNonNull(person, "person");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(slug, "slug");
        Assert.state(TransactionSynchronizationManager.isActualTransactionActive(),
                "A person rename must run inside a transaction");
        // Write pending changes first, so the raw statement runs after them.
        entityManager.flush();
        boolean renamed = entityManager.unwrap(Session.class)
                .doReturningWork(connection -> rename(connection, person.getId(), name, slug));
        if (renamed) {
            entityManager.refresh(person);
        }
        return renamed;
    }

    private static boolean rename(Connection connection, UUID id, String name, String slug) throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try (PreparedStatement statement = connection.prepareStatement(RENAME)) {
            statement.setString(1, name);
            statement.setString(2, slug);
            statement.setObject(3, id);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("The person to rename does not exist");
            }
        } catch (SQLException exception) {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
            if (UNIQUE_VIOLATION.equals(exception.getSQLState())) {
                return false;
            }
            throw exception;
        }
        connection.releaseSavepoint(savepoint);
        return true;
    }
}
