package com.vodplatform.worker.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.mock.env.MockEnvironment;

class WorkerJdbcConfigurationTests {
    private final WorkerJdbcConfiguration configuration = new WorkerJdbcConfiguration();

    @Test
    void usesScopedCredentialsAndBoundedWaits() {
        var source = (PGSimpleDataSource) configuration.workerDataSource(environment());
        assertThat(source.getServerNames()).containsExactly("postgres");
        assertThat(source.getPortNumbers()).containsExactly(5432);
        assertThat(source.getDatabaseName()).isEqualTo("vod_platform");
        assertThat(source.getUser()).isEqualTo("worker-only");
        assertThat(source.getPassword()).isEqualTo("worker-only-secret");
        assertThat(source.getLoginTimeout()).isEqualTo(5);
        assertThat(source.getConnectTimeout()).isEqualTo(5);
        assertThat(source.getSocketTimeout()).isEqualTo(10);
        assertThat(source.getOptions()).isEqualTo("-c statement_timeout=5000");
    }

    @ParameterizedTest
    @ValueSource(strings = {"WORKER_JDBC_CONNECT_TIMEOUT_SECONDS", "WORKER_JDBC_READ_TIMEOUT_SECONDS",
            "WORKER_JDBC_STATEMENT_TIMEOUT_MS"})
    void rejectsUnboundedOrMalformedBudgets(String name) {
        for (String invalid : new String[] {"0", "-1", "2147483648", "test-secret"}) {
            assertThatThrownBy(() -> configuration.workerDataSource(environment().withProperty(name, invalid)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(name + " must be a positive integer in range")
                    .hasNoCause();
        }
    }

    @Test
    void omitsAnInvalidUrlFromStartupDiagnostics() {
        assertThatThrownBy(() -> configuration.workerDataSource(
                environment().withProperty("SPRING_DATASOURCE_URL", "https://test-secret@postgres:5432")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("SPRING_DATASOURCE_URL must be a valid PostgreSQL JDBC URL")
                .hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD"})
    void omitsUnresolvableSettingsFromStartupDiagnostics(String name) {
        assertThatThrownBy(() -> configuration.workerDataSource(
                environment().withProperty(name, "synthetic-marker-${unresolved-test-property}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(name + " could not be resolved")
                .hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://localhost:5432?password=%s",
            "jdbc:postgresql://localhost:5432/vod?password=%s",
            "jdbc:postgresql://worker:%s@localhost:5432/vod",
            "jdbc:postgresql://localhost:invalid/vod?password=%s"
    })
    void malformedUrlDoesNotExposeItsPasswordInLogsOrException(String template) {
        String marker = "synthetic-jdbc-credential";
        String url = template.formatted(marker);
        var messages = new StringBuilder();
        var formatter = new SimpleFormatter();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.append(formatter.formatMessage(record));
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        Logger driver = Logger.getLogger("org.postgresql");
        driver.addHandler(capture);
        try {
            assertThatThrownBy(() -> configuration.workerDataSource(
                    environment().withProperty("SPRING_DATASOURCE_URL", url)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("SPRING_DATASOURCE_URL must be a valid PostgreSQL JDBC URL without credentials or parameters")
                    .hasNoCause();
            assertThat(messages.toString().contains(marker)).as("JDBC log contains a credential").isFalse();
        } finally {
            driver.removeHandler(capture);
        }
    }

    private static MockEnvironment environment() {
        return new MockEnvironment()
                .withProperty("SPRING_DATASOURCE_URL", "jdbc:postgresql://postgres:5432/vod_platform")
                .withProperty("SPRING_DATASOURCE_USERNAME", "worker-only")
                .withProperty("SPRING_DATASOURCE_PASSWORD", "worker-only-secret")
                .withProperty("WORKER_JDBC_CONNECT_TIMEOUT_SECONDS", "5")
                .withProperty("WORKER_JDBC_READ_TIMEOUT_SECONDS", "10")
                .withProperty("WORKER_JDBC_STATEMENT_TIMEOUT_MS", "5000");
    }
}
