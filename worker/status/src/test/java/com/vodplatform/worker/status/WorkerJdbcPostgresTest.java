package com.vodplatform.worker.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

@EnabledIfEnvironmentVariable(named = "WORKER_DEPENDENCY_SMOKE", matches = "true")
@Timeout(30)
class WorkerJdbcPostgresTest {
    @Test
    void connectsAsTheScopedWorkerWithABoundedStatement() throws SQLException {
        try (var context = new AnnotationConfigApplicationContext(WorkerJdbcConfiguration.class)) {
            assertEquals(Status.UP, context.getBean("workerDatabase", HealthIndicator.class).health().getStatus());
            try (var connection = context.getBean(DataSource.class).getConnection();
                    var statement = connection.createStatement()) {
                try (var rows = statement.executeQuery("SELECT current_user")) {
                    assertTrue(rows.next());
                    assertTrue(System.getenv("WORKER_TEST_POSTGRES_USER").equals(rows.getString(1)),
                            "JDBC must connect with the worker role");
                }
                try (var rows = statement.executeQuery("SHOW statement_timeout")) {
                    assertTrue(rows.next());
                    assertEquals("700ms", rows.getString(1));
                }
                try (var rows = statement.executeQuery("SELECT id FROM encoding_jobs LIMIT 1")) {
                    assertEquals(1, rows.getMetaData().getColumnCount());
                }
                assertEquals(0, statement.executeUpdate("UPDATE video_assets SET status = 'PROCESSING' WHERE false"));

                SQLException denied = assertThrows(SQLException.class,
                        () -> statement.executeQuery("SELECT id FROM users"));
                assertEquals("42501", denied.getSQLState());

                SQLException cancelled = assertThrows(SQLException.class,
                        () -> statement.executeQuery("SELECT pg_sleep(5)"));
                assertEquals("57014", cancelled.getSQLState(), "server must cancel at statement_timeout");
                try (var rows = statement.executeQuery("SELECT 1")) {
                    assertTrue(rows.next(), "statement timeout must leave the connection usable");
                }
            }
        }
    }
}
