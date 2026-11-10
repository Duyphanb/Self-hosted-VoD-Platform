package com.vodplatform.worker.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.mock.env.MockEnvironment;

class WorkerJdbcConfigurationTests {
    private final WorkerJdbcConfiguration configuration = new WorkerJdbcConfiguration();

    @Test
    void usesScopedCredentialsAndBoundedWaitsEvenWhenTheUrlSpecifiesOthers() {
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
                .hasMessage("SPRING_DATASOURCE_URL must be a valid PostgreSQL JDBC URL")
                .hasNoCause();
    }

    private static MockEnvironment environment() {
        return new MockEnvironment()
                .withProperty("SPRING_DATASOURCE_URL", "jdbc:postgresql://postgres:5432/vod_platform"
                        + "?user=bootstrap&password=not-used&connectTimeout=0&socketTimeout=0&loginTimeout=0"
                        + "&options=-c%20statement_timeout%3D0")
                .withProperty("SPRING_DATASOURCE_USERNAME", "worker-only")
                .withProperty("SPRING_DATASOURCE_PASSWORD", "worker-only-secret")
                .withProperty("WORKER_JDBC_CONNECT_TIMEOUT_SECONDS", "5")
                .withProperty("WORKER_JDBC_READ_TIMEOUT_SECONDS", "10")
                .withProperty("WORKER_JDBC_STATEMENT_TIMEOUT_MS", "5000");
    }
}
