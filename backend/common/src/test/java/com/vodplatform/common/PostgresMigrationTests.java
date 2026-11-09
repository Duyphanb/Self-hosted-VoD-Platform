package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.vodplatform.common.support.PostgresTestDatabase;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Issue #119: the Spring context tests run on PostgreSQL 16 with every committed Flyway migration,
 * and Hibernate validates the entity mappings against that schema instead of generating one.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "auth.tokens.secret=test-only-secret-with-at-least-32-bytes"
)
class PostgresMigrationTests implements PostgresTestDatabase {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void everyCommittedMigrationIsAppliedAndNoneIsPending() throws IOException {
        Resource[] scripts = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*__*.sql");
        List<MigrationVersion> committed = Arrays.stream(scripts)
                .map(Resource::getFilename)
                .filter(Objects::nonNull)
                .map(name -> MigrationVersion.fromVersion(name.substring(1, name.indexOf("__"))))
                .sorted()
                .toList();

        assertThat(committed).as("committed migrations").isNotEmpty();
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().applied())
                .extracting(MigrationInfo::getVersion)
                .containsExactlyElementsOf(committed);
        assertThat(flyway.info().applied())
                .extracting(MigrationInfo::getState)
                .containsOnly(MigrationState.SUCCESS);
    }

    @Test
    void theServerIsPostgresql16WithTheVietnameseIcuCollation() {
        assertThat(jdbc.queryForObject("select current_setting('server_version_num')::int / 10000", Integer.class))
                .isEqualTo(16);
        assertThat(jdbc.queryForMap(
                "select datlocprovider::text as provider, daticulocale as locale from pg_database"
                        + " where datname = current_database()"))
                .containsEntry("provider", "i")
                .containsEntry("locale", "vi");
    }

    @Test
    void hibernateOnlyValidatesTheMappingsAgainstTheMigratedSchema() {
        assertThat(entityManagerFactory.getProperties()).containsEntry("hibernate.hbm2ddl.auto", "validate");
    }

    @Test
    void theRolesComeFromTheV2AndV3Seeds() {
        assertThat(jdbc.queryForList("select name from roles order by name", String.class))
                .containsExactly("ROLE_ADMIN", "ROLE_USER");
    }
}
