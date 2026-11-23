package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vodplatform.auth.service.AuthSessionPruningJob;
import com.vodplatform.common.support.CookieAuthFixture;
import com.vodplatform.common.support.PostgresTestDatabase;
import jakarta.servlet.http.Cookie;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "auth.tokens.secret=test-only-secret-with-at-least-32-bytes",
        "CORS_ALLOWED_ORIGINS=http://localhost"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class AuthSessionConcurrencyIntegrationTests extends CookieAuthFixture implements PostgresTestDatabase {

    @Autowired DataSource dataSource;
    @Autowired AuthSessionPruningJob pruning;

    @Test
    void rotationsOfOneCookieWaitForTheSameOwnerAndExactlyOneSucceeds() throws Exception {
        Cookie old = issued();
        try (var workers = Executors.newFixedThreadPool(2); Connection held = holdRow("users", user)) {
            int blocker = backendPid(held);
            var first = workers.submit(() -> refresh(old).andReturn());
            var second = workers.submit(() -> refresh(old).andReturn());
            try {
                awaitBlocked(blocker, 2);
            } finally {
                held.commit();
            }
            var results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().map(result -> result.getResponse().getStatus()))
                    .containsExactlyInAnyOrder(200, 401);
            var winningResult = results.stream().filter(result -> result.getResponse().getStatus() == 200)
                    .findFirst().orElseThrow();
            refresh(cookie(winningResult)).andExpect(status().isUnauthorized());
        }
        assertThat(revokedSessions()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void replayAndSuccessorRotationAreSerialized(boolean replayFirst) throws Exception {
        Cookie original = issued();
        Cookie successor = cookie(refresh(original).andExpect(status().isOk()).andReturn());
        try (var workers = Executors.newFixedThreadPool(2); Connection held = holdRow("users", user)) {
            int blocker = backendPid(held);
            var first = workers.submit(() -> refresh(replayFirst ? original : successor).andReturn());
            try {
                awaitBlocked(blocker, 1);
                var second = workers.submit(() -> refresh(replayFirst ? successor : original).andReturn());
                awaitBlocked(blocker, 2);
                held.commit();
                var replay = (replayFirst ? first : second).get(10, TimeUnit.SECONDS);
                var rotation = (replayFirst ? second : first).get(10, TimeUnit.SECONDS);
                assertThat(replay.getResponse().getStatus()).isEqualTo(401);
                assertThat(rotation.getResponse().getStatus()).isEqualTo(replayFirst ? 401 : 200);
                if (!replayFirst) {
                    refresh(cookie(rotation)).andExpect(status().isUnauthorized());
                }
            } finally {
                held.rollback();
            }
        }
        assertThat(revokedSessions()).isEqualTo(1);
    }

    @Test
    void simultaneousLoginsAtTheCapEvictOnlyTheTwoOldestSessions() throws Exception {
        jdbc.update("""
                insert into auth_sessions (id, user_id, created_at, expires_at)
                select gen_random_uuid(), ?, ?::timestamptz + make_interval(secs => i), ?
                from generate_series(1, 10) i
                """, user, Timestamp.from(now.minusSeconds(20)), Timestamp.from(now.plusSeconds(60)));
        List<UUID> oldest = jdbc.queryForList("""
                select id from auth_sessions where user_id=? order by created_at, id limit 2
                """, UUID.class, user);
        try (var workers = Executors.newFixedThreadPool(2); Connection held = holdRow("users", user)) {
            int blocker = backendPid(held);
            var first = workers.submit(() -> login().andReturn().getResponse().getStatus());
            var second = workers.submit(() -> login().andReturn().getResponse().getStatus());
            try {
                awaitBlocked(blocker, 2);
            } finally {
                held.commit();
            }
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        }
        var revoked = jdbc.queryForList("select id from auth_sessions where user_id=? and revoked_at is not null",
                UUID.class, user);
        assertThat(revoked).containsExactlyInAnyOrderElementsOf(oldest);
        assertThat(jdbc.queryForObject("select count(*) from auth_sessions where user_id=? and revoked_at is null",
                Integer.class, user)).isEqualTo(10);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void logoutRevokesTheSessionEvenIfRefreshRotatesFirst(boolean logoutFirst) throws Exception {
        Cookie original = issued();
        UUID sessionId = singleId("auth_sessions");
        try (var workers = Executors.newFixedThreadPool(2); Connection held = holdRow("auth_sessions", sessionId)) {
            int blocker = backendPid(held);
            var first = workers.submit(() -> logoutFirst ? logout(original) : refresh(original).andReturn());
            try {
                awaitBlocked(blocker, 1);
                var second = workers.submit(() -> logoutFirst ? refresh(original).andReturn() : logout(original));
                awaitBlocked(blocker, 2);
                held.commit();
                var logout = (logoutFirst ? first : second).get(10, TimeUnit.SECONDS);
                var refresh = (logoutFirst ? second : first).get(10, TimeUnit.SECONDS);
                assertThat(logout.getResponse().getStatus()).isEqualTo(204);
                assertThat(refresh.getResponse().getStatus()).isEqualTo(logoutFirst ? 401 : 200);
                if (!logoutFirst) {
                    refresh(cookie(refresh)).andExpect(status().isUnauthorized());
                }
            } finally {
                held.rollback();
            }
        }
        assertThat(revokedSessions()).isEqualTo(1);
    }

    @Test
    void refreshWaitingBehindPruningCannotRecreateADeletedSession() throws Exception {
        Cookie original = issued();
        UUID tokenId = singleId("refresh_tokens");
        jdbc.update("update auth_sessions set expires_at=? where user_id=?",
                Timestamp.from(now.minus(Duration.ofDays(31))), user);
        try (var workers = Executors.newFixedThreadPool(2); Connection held = holdRow("refresh_tokens", tokenId)) {
            int blocker = backendPid(held);
            var prune = workers.submit(pruning::prune);
            try {
                awaitBlocked(blocker, 1);
                var rotation = workers.submit(() -> refresh(original).andReturn().getResponse().getStatus());
                awaitBlocked(blocker, 2);
                held.commit();
                prune.get(10, TimeUnit.SECONDS);
                assertThat(rotation.get(10, TimeUnit.SECONDS)).isEqualTo(401);
            } finally {
                held.rollback();
            }
        }
        assertThat(jdbc.queryForObject("select count(*) from auth_sessions where user_id=?", Integer.class, user))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id=?", Integer.class, user))
                .isZero();
    }

    @Test
    void pruningCrossesTheBatchBoundaryAndCascadesOnlySessionsPastRetention() throws Exception {
        issued();
        var cutoff = now.minus(Duration.ofDays(30));
        jdbc.update("""
                insert into auth_sessions (id, user_id, created_at, expires_at, revoked_at)
                select gen_random_uuid(), ?, ?::timestamptz - interval '7 days',
                       ?::timestamptz + interval '1 day', ?::timestamptz - interval '1 second'
                from generate_series(1, 501)
                """, user, Timestamp.from(cutoff), Timestamp.from(cutoff), Timestamp.from(cutoff));
        jdbc.update("""
                insert into auth_sessions (id, user_id, created_at, expires_at)
                values (gen_random_uuid(), ?, ?::timestamptz - interval '7 days', ?)
                """, user, Timestamp.from(cutoff), Timestamp.from(cutoff));
        jdbc.update("""
                insert into refresh_tokens (id, user_id, token_hash, expires_at, created_at, session_id)
                select gen_random_uuid(), user_id, id::text, expires_at, created_at, id
                from auth_sessions where user_id=? and revoked_at is not null
                """, user);
        jdbc.update("""
                insert into refresh_tokens (id, user_id, token_hash, expires_at, created_at)
                select id, ?, id::text, ?, ? from (select gen_random_uuid() id from generate_series(1, 501)) fixture
                """, user, Timestamp.from(now.plusSeconds(600)), Timestamp.from(now));

        assertPruningUsesBoundedTransactions();

        assertThat(jdbc.queryForObject("select count(*) from auth_sessions where user_id=?", Integer.class, user))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id=?", Integer.class, user))
                .isEqualTo(1);
        assertThat(revokedSessions()).isZero();
    }

    private void assertPruningUsesBoundedTransactions() {
        String auditTable = "auth_prune_" + UUID.randomUUID().toString().replace("-", "");
        String function = auditTable + "_fn";
        jdbc.execute("create table " + auditTable + " (source text, transaction_id xid8)");
        try {
            jdbc.execute("""
                    create function %s() returns trigger language plpgsql as $$
                    begin
                        if old.user_id::text = tg_argv[0] then
                            insert into %s values (tg_table_name, pg_current_xact_id());
                        end if;
                        return old;
                    end $$
                    """.formatted(function, auditTable));
            jdbc.execute("""
                    create trigger %s before delete on auth_sessions for each row execute function %s('%s')
                    """.formatted(auditTable, function, user));
            jdbc.execute("""
                    create trigger %s before delete on refresh_tokens for each row
                    when (old.session_id is null) execute function %s('%s')
                    """.formatted(auditTable, function, user));

            pruning.prune();

            for (String table : List.of("auth_sessions", "refresh_tokens")) {
                assertThat(jdbc.queryForList("""
                        select count(*) from %s where source=? group by transaction_id
                        """.formatted(auditTable), Integer.class, table)).containsExactlyInAnyOrder(500, 1);
            }
        } finally {
            jdbc.execute("drop trigger if exists " + auditTable + " on refresh_tokens");
            jdbc.execute("drop trigger if exists " + auditTable + " on auth_sessions");
            jdbc.execute("drop function if exists " + function + "()");
            jdbc.execute("drop table " + auditTable);
        }
    }

    private MvcResult logout(Cookie cookie) throws Exception {
        return mvc.perform(post("/api/v1/auth/logout").header("Origin", ORIGIN).cookie(cookie)).andReturn();
    }

    private UUID singleId(String table) {
        return jdbc.queryForObject("select id from " + table + " where user_id=?", UUID.class, user);
    }

    private Connection holdRow(String table, UUID id) throws Exception {
        Connection connection = dataSource.getConnection();
        try {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("select id from " + table + " where id = ? for update")) {
                lock.setObject(1, id);
                try (var row = lock.executeQuery()) {
                    assertThat(row.next()).isTrue();
                }
            }
            return connection;
        } catch (Exception | AssertionError failure) {
            connection.close();
            throw failure;
        }
    }

    private int backendPid(Connection held) throws Exception {
        try (var query = held.createStatement(); var row = query.executeQuery("select pg_backend_pid()")) {
            assertThat(row.next()).isTrue();
            return row.getInt(1);
        }
    }

    private void awaitBlocked(int holder, int count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        do {
            Integer waiting = jdbc.queryForObject("""
                    with recursive blocked(pid) as (
                        select ?::int
                        union
                        select a.pid from pg_stat_activity a join blocked b on b.pid=any(pg_blocking_pids(a.pid))
                        where a.datname=current_database() and a.wait_event_type='Lock'
                    )
                    select count(*) from blocked where pid<>?
                    """, Integer.class, holder, holder);
            if (waiting >= count) {
                return;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Expected " + count + " PostgreSQL lock wait(s) behind connection " + holder);
    }
}
