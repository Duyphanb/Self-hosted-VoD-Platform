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
import com.vodplatform.auth.service.SessionStateReader;
import com.vodplatform.common.support.CookieAuthFixture;
import com.vodplatform.common.support.PostgresTestDatabase;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.dao.DataAccessResourceFailureException;
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
        "CORS_ALLOWED_ORIGINS=http://localhost"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class SessionSecurityIntegrationTests extends CookieAuthFixture implements PostgresTestDatabase {

    private static final String PROFILE = "/api/v1/users/me";
    private static final String ADMIN = "/api/v1/admin/movies";
    private static final String HLS = "/hls/video-assets/00000000-0000-0000-0000-000000000001/master.m3u8";

    @Autowired JwtEncoder encoder;
    @Autowired JwtDecoder decoder;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean Ticker ticker;
    @MockitoSpyBean AuthSessionRepository sessions;
    @MockitoSpyBean SessionStateReader reader;

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
        verify(reader, times(1)).read(sessionId(), false);
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
        verify(reader, times(1)).read(sessionId(), false);

        transaction.executeWithoutResult(state -> logoutAndCheckCache(login, profile));
        mvc.perform(profile).andExpect(status().isUnauthorized());
        verify(reader, times(2)).read(sessionId(), false);
    }

    @Test
    void aLoadTakingOverThirtySecondsCannotAuthorizeAfterAnOperatorRevokesInSql() throws Exception {
        var login = signedIn();
        UUID id = sessionId();
        var selected = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object oldSnapshot = invocation.callRealMethod();
            selected.countDown();
            assertThat(resume.await(10, TimeUnit.SECONDS)).isTrue();
            return oldSnapshot;
        }).doCallRealMethod().when(reader).read(id, false);
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
        verify(reader, times(2)).read(id, false);
    }

    private void logoutAndCheckCache(MvcResult login, MockHttpServletRequestBuilder profile) {
        try {
            mvc.perform(post("/api/v1/auth/logout").header("Origin", ORIGIN).cookie(cookie(login)))
                    .andExpect(status().isNoContent());
            mvc.perform(profile).andExpect(status().isOk());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private MvcResult signedIn() throws Exception {
        return login().andExpect(status().isOk()).andReturn();
    }

    private UUID sessionId() {
        return jdbc.queryForObject("select id from auth_sessions where user_id=?", UUID.class, user);
    }

    private void assertUnauthorized(String authorization) throws Exception {
        mvc.perform(get(PROFILE).header("Authorization", authorization))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
