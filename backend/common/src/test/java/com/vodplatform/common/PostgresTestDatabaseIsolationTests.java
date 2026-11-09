package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.vodplatform.common.support.PostgresTestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Issue #119: a context test cannot reach a developer database. These properties outrank an
 * exported {@code SPRING_DATASOURCE_*} or {@code SPRING_FLYWAY_*} variable, and the application and
 * Flyway still connect to the test container, whose {@code @ServiceConnection} details replace the
 * property-based ones.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "auth.tokens.secret=test-only-secret-with-at-least-32-bytes",
                "spring.datasource.url=jdbc:postgresql://developer-database.invalid:5432/vod_platform",
                "spring.datasource.username=vod_app",
                "spring.datasource.password=developer-only",
                "spring.flyway.url=jdbc:postgresql://developer-database.invalid:5432/vod_platform",
                "spring.flyway.user=vod_app",
                "spring.flyway.password=developer-only"
        }
)
class PostgresTestDatabaseIsolationTests implements PostgresTestDatabase {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway flyway;

    @Test
    void theApplicationAndFlywayConnectToTheTestContainerInsteadOfTheConfiguredDatabase() throws SQLException {
        assertThat(urlOf(dataSource)).isEqualTo(POSTGRES.getJdbcUrl());
        assertThat(urlOf(flyway.getConfiguration().getDataSource())).isEqualTo(POSTGRES.getJdbcUrl());
    }

    private static String urlOf(DataSource source) throws SQLException {
        try (Connection connection = source.getConnection()) {
            return connection.getMetaData().getURL();
        }
    }
}
