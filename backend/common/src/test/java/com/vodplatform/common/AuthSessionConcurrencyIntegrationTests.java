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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;

@SpringBootTest(properties = {
        "auth.tokens.secret=test-only-secret-with-at-least-32-bytes",
        "CORS_ALLOWED_ORIGINS=http://localhost"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class AuthSessionConcurrencyIntegrationTests extends CookieAuthFixture implements PostgresTestDatabase {

    @Autowired DataSource dataSource;
    @Autowired AuthSessionPruningJob pruning;
    @Autowired @Qualifier("taskScheduler") TaskScheduler relayScheduler;
    @Autowired @Qualifier("authPruningScheduler") TaskScheduler pruningScheduler;

    @Test
    void loginAndRefreshCanCommitWhileTheUserHasAForeignKeyLock() throws Exception {
        try (var worker = Executors.newSingleThreadExecutor();
             Connection held = holdRow("users", user, "FOR KEY SHARE")) {
            var requests = worker.submit(() -> {
                var login = login().andExpect(status().isOk()).andReturn();
                refresh(cookie(login)).andExpect(status().isOk());
                return null;
            });
            try {
                requests.get(5, TimeUnit.SECONDS);
            } finally {
                held.rollback();
            }
        }
    }

    @Test
    void aRefreshBehindALongUserLockIs503BeforeTheSpaGivesUpAndRotatesNothing() throws Exception {
        Cookie original = issued();
        String tokenHashQuery = "select token_hash from refresh_tokens where user_id=?";
        String hashBefore = jdbc.queryForObject(tokenHashQuery, String.class, user);
        try (Connection held = holdRow("users", user, "FOR UPDATE")) {
            assertUnavailableBeforeTheSpaGivesUp(held, () -> refresh(original).andReturn().getResponse());
        }
        assertThat(jdbc.queryForList(tokenHashQuery, String.class, user)).containsExactly(hashBefore);
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id=? and revoked_at is not null",
                Integer.class, user)).isZero();
    }

    @Test
    void aLoginBehindALongUserLockIs503BeforeTheSpaGivesUpAndStartsNoSession() throws Exception {
        String sessionCount = "select count(*) from auth_sessions where user_id=?";
        Integer sessionsBefore = jdbc.queryForObject(sessionCount, Integer.class, user);
        try (Connection held = holdRow("users", user, "FOR UPDATE")) {
            assertUnavailableBeforeTheSpaGivesUp(held, () -> login().andReturn().getResponse());
        }
        assertThat(jdbc.queryForObject(sessionCount, Integer.class, user)).isEqualTo(sessionsBefore);
    }

    @Test
    void aLogoutBehindALongSessionLockIs503BeforeTheSpaGivesUpAndRevokesNothing() throws Exception {
        Cookie original = issued();
        UUID sessionId = singleId("auth_sessions");
        try (Connection held = holdRow("auth_sessions", sessionId, "FOR UPDATE")) {
            assertUnavailableBeforeTheSpaGivesUp(held, () -> mvc.perform(post("/api/v1/auth/logout")
                    .header("Origin", ORIGIN).cookie(original)).andReturn().getResponse());
        }
        assertThat(jdbc.queryForObject("select count(*) from auth_sessions where id=? and revoked_at is null",
                Integer.class, sessionId)).isOne();
    }

    @Test
    void pruningUsesItsOwnSchedulerAndCannotOccupyTheRelayScheduler() throws Exception {
        var scheduled = AuthSessionPruningJob.class.getMethod("prune").getAnnotation(Scheduled.class);
        assertThat(scheduled.scheduler()).isEqualTo("authPruningScheduler");
        var pruningStarted = new CountDownLatch(1);
        var releasePruning = new CountDownLatch(1);
        var relayRan = new CountDownLatch(1);
        var held = pruningScheduler.schedule(() -> {
            pruningStarted.countDown();
            try {
                releasePruning.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, Instant.now());
        try {
            assertThat(pruningStarted.await(5, TimeUnit.SECONDS)).isTrue();
            relayScheduler.schedule(relayRan::countDown, Instant.now());
            assertThat(relayRan.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            releasePruning.countDown();
            held.get(5, TimeUnit.SECONDS);
        }
    }

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
    void refreshWaitingBehindPruningCannotRotateAnEndedSession() throws Exception {
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
        // A sweep can skip the session while the rejected refresh still owns its row lock.
        pruning.prune();
        assertThat(jdbc.queryForObject("select count(*) from auth_sessions where user_id=?", Integer.class, user))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id=?", Integer.class, user))
                .isZero();
    }

    @Test
    void pruningBoundsTheCombinedRowCountEvenForASessionWithManyTokens() throws Exception {
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
        UUID largestSession = jdbc.queryForObject("""
                select id from auth_sessions where user_id=? and revoked_at is not null order by id limit 1
                """, UUID.class, user);
        jdbc.update("""
                insert into refresh_tokens (id, user_id, token_hash, expires_at, created_at, session_id)
                select id, ?, id::text, ?, ?, ?
                from (select gen_random_uuid() id from generate_series(1, 701)) fixture
                """, user, Timestamp.from(cutoff), Timestamp.from(cutoff), largestSession);
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
                    execute function %s('%s')
                    """.formatted(auditTable, function, user));

            pruning.prune();

            assertThat(jdbc.queryForList("""
                    select count(*) from %s group by transaction_id
                    """.formatted(auditTable), Integer.class)).isNotEmpty().allMatch(count -> count <= 500);
            assertThat(jdbc.queryForObject("select count(*) from " + auditTable, Integer.class)).isEqualTo(1703);
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

    // The SPA aborts login, refresh and logout after 10 s (authSessionLock.ts), so the typed 503 must come first.
    private void assertUnavailableBeforeTheSpaGivesUp(Connection held, Callable<MockHttpServletResponse> request)
            throws Exception {
        try (var worker = Executors.newSingleThreadExecutor()) {
            long started = System.nanoTime();
            var pending = worker.submit(request);
            try {
                var response = pending.get(20, TimeUnit.SECONDS);
                assertThat(response.getStatus()).isEqualTo(503);
                assertThat(response.getContentAsString()).contains("AUTH_STATE_UNAVAILABLE");
                assertThat(response.getHeader("Set-Cookie")).isNull();
                // The 5-second statement timeout cancels the lock wait and keeps the connection; the 7-second
                // network timeout would also end it, but by discarding the connection.
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(6_500));
            } finally {
                held.rollback();
            }
        }
    }

    private Connection holdRow(String table, UUID id) throws Exception {
        return holdRow(table, id, "FOR UPDATE");
    }

    private Connection holdRow(String table, UUID id, String lockMode) throws Exception {
        Connection connection = dataSource.getConnection();
        try {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("select id from " + table + " where id = ? " + lockMode)) {
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
