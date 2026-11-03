package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.vodplatform.common.support.PostgresTestDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * ADR-008 on PostgreSQL (issue #119): the unique index {@code ux_users_email_identity} on
 * {@code lower(email)} decides a case-variant registration that the duplicate pre-check cannot see.
 * Another transaction holds an uncommitted {@code VIEWER@example.com}; the registration of
 * {@code viewer@example.com} passes the pre-check, waits on the index entry and, once the other
 * transaction commits, fails on that index and returns 409 {@code EMAIL_ALREADY_EXISTS}. The
 * exact-spelling constraint {@code ux_users_email} cannot match the two spellings, and an unmapped
 * violation would be a 500. H2 schemas have no expression index, so only PostgreSQL shows this.
 */
@SpringBootTest(properties = "auth.tokens.secret=test-only-secret-with-at-least-32-bytes")
@AutoConfigureMockMvc
class EmailIdentityPostgresTests implements PostgresTestDatabase {

    private static final String HELD_EMAIL = "VIEWER@example.com";
    private static final String REGISTERED_EMAIL = "viewer@example.com";
    private static final long TIMEOUT_SECONDS = 30;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void removeTheAccount() {
        jdbc.update("delete from users where lower(email) = ?", REGISTERED_EMAIL);
    }

    @Test
    void aCaseVariantRegistrationThatPassesThePreCheckIsAConflictOnTheIdentityIndex() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            insertAccount(holder, HELD_EMAIL);

            Future<MvcResult> registration = executor.submit(() -> mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsBytes(Map.of(
                                    "email", REGISTERED_EMAIL, "password", "strong-password", "displayName", "Racer"))))
                    .andReturn());
            assertThat(awaitInsertWaitingForLock())
                    .as("the registration insert waits on the uncommitted index entry")
                    .isTrue();
            holder.commit();

            MvcResult result = registration.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("code").asString())
                    .isEqualTo("EMAIL_ALREADY_EXISTS");
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForList("select email from users where lower(email) = ?", String.class, REGISTERED_EMAIL))
                .as("one account, with the spelling it was created with")
                .containsExactly(HELD_EMAIL);
    }

    private static void insertAccount(Connection connection, String email) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into users (id, email, password_hash, display_name, status, created_at, updated_at)"
                        + " values (?, ?, 'fixture-hash', 'Holder', 'ACTIVE', now(), now())")) {
            insert.setObject(1, UUID.randomUUID());
            insert.setString(2, email);
            insert.executeUpdate();
        }
    }

    /**
     * Polls until a statement inserting into {@code users} waits for a lock, and returns false when
     * none did before the timeout.
     */
    private boolean awaitInsertWaitingForLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from pg_stat_activity where datname = current_database()"
                            + " and wait_event_type = 'Lock' and query like 'insert into users%')",
                    Boolean.class))) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }
}
