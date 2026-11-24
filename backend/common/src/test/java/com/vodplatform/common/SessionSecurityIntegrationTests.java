package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.benmanes.caffeine.cache.Ticker;
import com.vodplatform.auth.persistence.AuthSessionRepository;
import com.vodplatform.auth.service.AuthSessionService;
import com.vodplatform.auth.service.SessionStateReader;
import com.vodplatform.common.support.CookieAuthFixture;
import com.vodplatform.common.support.PostgresTestDatabase;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "auth.tokens.secret=test-only-secret-with-at-least-32-bytes",
        "CORS_ALLOWED_ORIGINS=http://localhost",
        // Short enough for the stalled-database tests; the default is 5 s.
        "auth.sessions.state-read-timeout=1s"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class SessionSecurityIntegrationTests extends CookieAuthFixture implements PostgresTestDatabase {

    private static final String PROFILE = "/api/v1/users/me";
    private static final String ADMIN = "/api/v1/admin/movies";
    private static final String HLS = "/hls/video-assets/00000000-0000-0000-0000-000000000001/master.m3u8";

    @Autowired JwtEncoder encoder;
    @Autowired JwtDecoder decoder;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DataSource dataSource;
    @MockitoBean Ticker ticker;
    @MockitoSpyBean AuthSessionRepository sessions;
    @MockitoSpyBean SessionStateReader reader;
    @MockitoSpyBean AuthSessionService authSessions;

    private final AtomicLong elapsedNanos = new AtomicLong();

    @BeforeEach
    void controlCacheTime() {
        when(ticker.read()).thenAnswer(invocation -> elapsedNanos.get());
    }

    @Test
    void accessTokenDefaultsToFiveMinutesAndAppliesExactlyFiveSecondsOfSkew() throws Exception {
        var login = signedIn();
        String authorization = bearer(login);
        var claims = decoder.decode(authorization.substring("Bearer ".length()));
        assertThat(Duration.between(claims.getIssuedAt(), claims.getExpiresAt())).isEqualTo(Duration.ofMinutes(5));
        assertThat(((Number) claims.getClaim("ver")).intValue()).isZero();
        assertThat(claims.getClaimAsString("sid")).isEqualTo(sessionId().toString());
        when(clock.instant()).thenReturn(claims.getExpiresAt().plusSeconds(5));
        mvc.perform(get(PROFILE).header("Authorization", authorization)).andExpect(status().isOk());
        when(clock.instant()).thenReturn(claims.getExpiresAt().plusSeconds(5).plusNanos(1));
        mvc.perform(get(PROFILE).header("Authorization", authorization)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sid", "ver"})
    void legacyAccessTokenMissingEitherSessionClaimIsUnauthorized(String missingClaim) throws Exception {
        signedIn();
        var claims = JwtClaimsSet.builder().subject(user.toString()).issuedAt(now).expiresAt(now.plusSeconds(300))
                .claim("userId", user.toString()).claim("email", email).claim("roles", List.of("ROLE_USER"))
                .claim("sid", sessionId().toString()).claim("ver", 0);
        claims.claims(values -> values.remove(missingClaim));
        var header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String legacy = encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
        mvc.perform(get(PROFILE).header("Authorization", "Bearer " + legacy)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "expired", "revoked", "disabled", "epoch", "owner"})
    void invalidSessionStateIsUnauthorized(String invalid) throws Exception {
        String authorization = bearer(signedIn());
        switch (invalid) {
            case "missing" -> jdbc.update("delete from auth_sessions where user_id=?", user);
            case "expired" -> jdbc.update("update auth_sessions set expires_at=? where user_id=?",
                    Timestamp.from(now), user);
            case "revoked" -> jdbc.update("update auth_sessions set revoked_at=? where user_id=?",
                    Timestamp.from(now), user);
            case "disabled" -> jdbc.update("update users set status='DISABLED' where id=?", user);
            case "epoch" -> jdbc.update("update users set auth_epoch=1 where id=?", user);
            case "owner" -> {
                UUID otherUser = UUID.randomUUID();
                jdbc.update("""
                        insert into users (id,email,password_hash,display_name,status,created_at,updated_at)
                        values (?,?,'unused','Other viewer','ACTIVE',now(),now())
                        """, otherUser, otherUser + "@example.com");
                try {
                    jdbc.update("update auth_sessions set user_id=? where user_id=?", otherUser, user);
                    assertUnauthorized(authorization);
                } finally {
                    jdbc.update("delete from users where id=?", otherUser);
                }
                return;
            }
            default -> throw new IllegalArgumentException(invalid);
        }
        assertUnauthorized(authorization);
    }

    @Test
    void adminRequestsUseLiveRoleEvenWhileTheOrdinaryBearerCacheIsWarm() throws Exception {
        jdbc.update("insert into user_roles (user_id, role_id) select ?, id from roles where name='ROLE_ADMIN'", user);
        String authorization = bearer(signedIn());
        mvc.perform(get(PROFILE).header("Authorization", authorization)).andExpect(status().isOk());
        mvc.perform(get(ADMIN).header("Authorization", authorization)).andExpect(status().isOk());
        jdbc.update("delete from user_roles where user_id=? and role_id=(select id from roles where name='ROLE_ADMIN')",
                user);
        mvc.perform(get(ADMIN).header("Authorization", authorization)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/movies").header("Authorization", authorization)).andExpect(status().isForbidden());
        verify(reader, times(3)).read(sessionId(), true);
        verify(reader, times(1)).readAsync(sessionId(), false);
    }

    @Test
    void liveAdminRoleDoesNotElevateABearerTokenWithOnlyTheUserClaim() throws Exception {
        String userAuthorization = bearer(signedIn());
        // Keep the JWT epoch valid to isolate the role check from runbook-wide revocation.
        jdbc.update("insert into user_roles (user_id, role_id) select ?, id from roles where name='ROLE_ADMIN'", user);
        mvc.perform(get(PROFILE).header("Authorization", userAuthorization)).andExpect(status().isOk());
        mvc.perform(get(ADMIN).header("Authorization", userAuthorization)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/movies").header("Authorization", userAuthorization))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {PROFILE, ADMIN, HLS})
    void bearerReadFailureIs503WithoutSessionLossOrAnAuthenticationChallenge(String path) throws Exception {
        var login = signedIn();
        doThrow(new DataAccessResourceFailureException("fixture")).when(sessions)
                .readState(sessionId(), ADMIN.equals(path));
        mvc.perform(get(path).header("Authorization", bearer(login)).cookie(cookie(login)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_STATE_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Set-Cookie")).andExpect(header().doesNotExist("WWW-Authenticate"));
        assertThat(revokedSessions()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {PROFILE, ADMIN, HLS})
    void aStalledDatabaseIs503WithinTheReadTimeoutAndKeepsTheSession(String path) throws Exception {
        var login = signedIn();
        var request = get(path).header("Authorization", bearer(login)).cookie(cookie(login));
        try (Connection holder = lockSessionTable()) {
            long started = System.nanoTime();
            mvc.perform(request)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("AUTH_STATE_UNAVAILABLE"))
                    .andExpect(header().doesNotExist("Set-Cookie"))
                    .andExpect(header().doesNotExist("WWW-Authenticate"));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
            holder.rollback();
        }
        assertThat(revokedSessions()).isZero();
        mvc.perform(get(PROFILE).header("Authorization", bearer(login))).andExpect(status().isOk());
    }

    @Test
    void aRequestSharingAStalledLoadAlsoGets503WithinTheReadTimeout() throws Exception {
        var login = signedIn();
        UUID id = sessionId();
        var request = get(PROFILE).header("Authorization", bearer(login));
        try (var callers = Executors.newFixedThreadPool(2); Connection holder = lockSessionTable()) {
            var loader = callers.submit(() -> mvc.perform(request).andReturn().getResponse().getStatus());
            awaitReadBlockedBy(holder);
            long started = System.nanoTime();
            var sharing = callers.submit(() -> mvc.perform(request).andReturn().getResponse().getStatus());

            assertThat(loader.get(5, TimeUnit.SECONDS)).isEqualTo(503);
            assertThat(sharing.get(5, TimeUnit.SECONDS)).isEqualTo(503);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
            holder.rollback();
        }
        verify(reader, times(1)).readAsync(id, false);
    }

    @Test
    void requestsDuringASustainedStallShareOnePhysicalReadAndTheNextRequestSucceedsAfterwards() throws Exception {
        var login = signedIn();
        UUID id = sessionId();
        var profile = get(PROFILE).header("Authorization", bearer(login));
        try (Connection holder = lockSessionTable()) {
            for (int request = 0; request < 3; request++) {
                mvc.perform(profile)
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.code").value("AUTH_STATE_UNAVAILABLE"));
            }
            verify(reader, times(1)).readAsync(id, false);
            holder.rollback();
        }

        mvc.perform(profile).andExpect(status().isOk());
        verify(reader, times(1)).readAsync(id, false);
    }

    @Test
    void theSessionReadRunsOnAConnectionWhoseNetworkTimeoutEndsADeadRead() throws Exception {
        var login = signedIn();
        var networkTimeoutMillis = new AtomicInteger(-1);
        doAnswer(invocation -> {
            // The transaction-bound connection is the one the query is about to use.
            networkTimeoutMillis.set(DataSourceUtils.getConnection(dataSource).getNetworkTimeout());
            return Optional.empty();
        }).when(sessions).readState(sessionId(), false);

        mvc.perform(get(PROFILE).header("Authorization", bearer(login))).andExpect(status().isUnauthorized());

        assertThat(networkTimeoutMillis.get()).isEqualTo(25_000);
    }

    @Test
    void loginRefreshAndLogoutLimitEachSocketWaitToTheTimeLeftInTheirRequest() throws Exception {
        var networkTimeouts = new CopyOnWriteArrayList<Integer>();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            networkTimeouts.add(DataSourceUtils.getConnection(dataSource).getNetworkTimeout());
            return null;
        }).when(authSessions).limitTransactionConnection();

        var login = signedIn();
        var refreshed = refresh(cookie(login)).andExpect(status().isOk()).andReturn();
        mvc.perform(post("/api/v1/auth/logout").header("Origin", ORIGIN).cookie(cookie(refreshed)))
                .andExpect(status().isNoContent());

        // Each request has 6 s from its arrival; the connection never waits longer than what is left.
        assertThat(networkTimeouts).hasSize(3).allMatch(millis -> millis > 0 && millis <= 6_000);
    }

    @Test
    void loginAndLogoutReadFailuresPreserveTheCookie() throws Exception {
        var existing = issued();
        doThrow(new DataAccessResourceFailureException("fixture")).when(tokens).locate(anyString());
        login(existing).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_STATE_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(post("/api/v1/auth/logout").header("Origin", ORIGIN).cookie(existing))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_STATE_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(revokedSessions()).isZero();
    }

    @Test
    void logoutInvalidationRunsOnlyAfterCommitAndIsDroppedOnRollback() throws Exception {
        var login = signedIn();
        var profile = get(PROFILE).header("Authorization", bearer(login));
        mvc.perform(profile).andExpect(status().isOk());
        var transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(state -> {
            logoutAndCheckCache(login, profile);
            state.setRollbackOnly();
        });
        mvc.perform(profile).andExpect(status().isOk());
        verify(reader, times(1)).readAsync(sessionId(), false);

        transaction.executeWithoutResult(state -> logoutAndCheckCache(login, profile));
        mvc.perform(profile).andExpect(status().isUnauthorized());
        verify(reader, times(2)).readAsync(sessionId(), false);
    }

    @Test
    void aLoadTakingOverThirtySecondsCannotAuthorizeAfterAnOperatorRevokesInSql() throws Exception {
        var login = signedIn();
        UUID id = sessionId();
        var selected = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        doAnswer(invocation -> {
            CompletableFuture<?> physicalRead = (CompletableFuture<?>) invocation.callRealMethod();
            return physicalRead.thenApply(oldSnapshot -> {
                selected.countDown();
                awaitQuietly(resume);
                return oldSnapshot;
            });
        }).doCallRealMethod().when(reader).readAsync(id, false);
        try (var caller = Executors.newSingleThreadExecutor()) {
            var response = caller.submit(() -> mvc.perform(get(PROFILE).header("Authorization", bearer(login)))
                    .andReturn().getResponse().getStatus());
            try {
                assertThat(selected.await(10, TimeUnit.SECONDS)).isTrue();
                jdbc.update("update auth_sessions set revoked_at=? where id=?", Timestamp.from(now), id);
                elapsedNanos.set(Duration.ofSeconds(31).toNanos());
            } finally {
                resume.countDown();
            }
            assertThat(response.get(10, TimeUnit.SECONDS)).isEqualTo(401);
        }
        verify(reader, times(2)).readAsync(id, false);
    }

    private void logoutAndCheckCache(MvcResult login, MockHttpServletRequestBuilder profile) {
        try {
            mvc.perform(post("/api/v1/auth/logout").header("Origin", ORIGIN).cookie(cookie(login)))
                    .andExpect(status().isNoContent());
            // Like a real request, the profile check runs outside this transaction, so it reads the cache.
            try (var otherRequest = Executors.newSingleThreadExecutor()) {
                int status = otherRequest.submit(() -> mvc.perform(profile).andReturn().getResponse().getStatus())
                        .get(10, TimeUnit.SECONDS);
                assertThat(status).isEqualTo(200);
            }
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private MvcResult signedIn() throws Exception {
        return login().andExpect(status().isOk()).andReturn();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    // A real stall: the session-state query waits for this table lock until the holder rolls back.
    private Connection lockSessionTable() throws Exception {
        Connection connection = dataSource.getConnection();
        try {
            connection.setAutoCommit(false);
            try (var lock = connection.createStatement()) {
                lock.execute("lock table auth_sessions in access exclusive mode");
            }
            return connection;
        } catch (Exception | AssertionError failure) {
            connection.close();
            throw failure;
        }
    }

    private void awaitReadBlockedBy(Connection holder) throws Exception {
        int holderPid;
        try (var query = holder.createStatement(); var row = query.executeQuery("select pg_backend_pid()")) {
            assertThat(row.next()).isTrue();
            holderPid = row.getInt(1);
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Integer blocked = jdbc.queryForObject(
                    "select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid))",
                    Integer.class, holderPid);
            if (blocked > 0) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("The session-state read never waited for the table lock");
    }

    private UUID sessionId() {
        return jdbc.queryForObject("select id from auth_sessions where user_id=?", UUID.class, user);
    }

    private void assertUnauthorized(String authorization) throws Exception {
        mvc.perform(get(PROFILE).header("Authorization", authorization))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
