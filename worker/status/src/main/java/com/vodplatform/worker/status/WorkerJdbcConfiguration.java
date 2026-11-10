package com.vodplatform.worker.status;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class WorkerJdbcConfiguration {
    @Bean
    DataSource workerDataSource(Environment env) {
        String url = required(env, "SPRING_DATASOURCE_URL");
        String username = required(env, "SPRING_DATASOURCE_USERNAME");
        String password = required(env, "SPRING_DATASOURCE_PASSWORD");
        int connect = positiveInt(env, "WORKER_JDBC_CONNECT_TIMEOUT_SECONDS", Integer.MAX_VALUE / 1000);
        int read = positiveInt(env, "WORKER_JDBC_READ_TIMEOUT_SECONDS", Integer.MAX_VALUE / 1000);
        int statement = positiveInt(env, "WORKER_JDBC_STATEMENT_TIMEOUT_MS", Integer.MAX_VALUE);

        // Configure the URL first so it cannot override the scoped identity or the explicit budgets.
        PGSimpleDataSource source = new PGSimpleDataSource();
        try {
            source.setURL(url);
        } catch (IllegalArgumentException ignored) {
            throw new IllegalArgumentException("SPRING_DATASOURCE_URL must be a valid PostgreSQL JDBC URL");
        }
        source.setUser(username);
        source.setPassword(password);
        source.setLoginTimeout(connect);
        source.setConnectTimeout(connect);
        source.setSocketTimeout(read);
        source.setOptions("-c statement_timeout=" + statement);
        return source;
    }

    @Bean(name = "workerDatabase")
    HealthIndicator workerDatabaseHealth(DataSource source) {
        return () -> {
            try (var connection = source.getConnection();
                    var statement = connection.prepareStatement("SELECT 1");
                    var rows = statement.executeQuery()) {
                if (rows.next() && rows.getInt(1) == 1) return Health.up().build();
            } catch (SQLException ignored) {
                // Driver diagnostics may contain database or credential details.
            }
            return Health.down().withDetail("database", "Worker database probe failed").build();
        };
    }

    private static String required(Environment env, String name) {
        String value = env.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static int positiveInt(Environment env, String name, int maximum) {
        try {
            int value = Integer.parseInt(env.getProperty(name));
            if (value > 0 && value <= maximum) return value;
        } catch (NumberFormatException ignored) {
            // Report the variable name without its configured value.
        }
        throw new IllegalArgumentException(name + " must be a positive integer in range");
    }
}
