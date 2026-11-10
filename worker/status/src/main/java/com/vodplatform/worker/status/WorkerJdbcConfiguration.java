package com.vodplatform.worker.status;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.PlaceholderResolutionException;

@Configuration(proxyBeanMethods = false)
public class WorkerJdbcConfiguration {
    @Bean
    WorkerJobStatus workerJobStatus(DataSource source) {
        return new WorkerJobStatus(source);
    }

    @Bean
    DataSource workerDataSource(Environment env) {
        URI target = postgresTarget(required(env, "SPRING_DATASOURCE_URL"));
        String username = required(env, "SPRING_DATASOURCE_USERNAME");
        String password = required(env, "SPRING_DATASOURCE_PASSWORD");
        int connect = positiveInt(env, "WORKER_JDBC_CONNECT_TIMEOUT_SECONDS", Integer.MAX_VALUE / 1000);
        int read = positiveInt(env, "WORKER_JDBC_READ_TIMEOUT_SECONDS", Integer.MAX_VALUE / 1000);
        int statement = positiveInt(env, "WORKER_JDBC_STATEMENT_TIMEOUT_MS", Integer.MAX_VALUE);

        // pgJDBC's URL parser can log a malformed URL before rejecting it.
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setServerNames(new String[] {target.getHost()});
        if (target.getPort() != -1) source.setPortNumbers(new int[] {target.getPort()});
        source.setDatabaseName(target.getPath().substring(1));
        source.setUser(username);
        source.setPassword(password);
        source.setLoginTimeout(connect);
        source.setConnectTimeout(connect);
        source.setSocketTimeout(read);
        source.setOptions("-c statement_timeout=" + statement);
        return source;
    }

    private static URI postgresTarget(String url) {
        String prefix = "jdbc:postgresql://";
        if (!url.startsWith(prefix)) throw invalidUrl();
        URI target;
        try {
            target = new URI(url.substring("jdbc:".length()));
        } catch (URISyntaxException ignored) {
            throw invalidUrl();
        }
        if (target.getHost() == null || target.getRawUserInfo() != null || target.getRawQuery() != null
                || target.getRawFragment() != null || target.getRawAuthority().endsWith(":")
                || target.getPort() == 0 || target.getPort() > 65_535
                || !target.getPath().matches("/[^/\\p{Cntrl}]+")) {
            throw invalidUrl();
        }
        return target;
    }

    private static IllegalArgumentException invalidUrl() {
        return new IllegalArgumentException("SPRING_DATASOURCE_URL must be a valid PostgreSQL JDBC URL without credentials or parameters");
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
        String value;
        try {
            value = env.getProperty(name);
        } catch (PlaceholderResolutionException ignored) {
            throw new IllegalArgumentException(name + " could not be resolved");
        }
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
