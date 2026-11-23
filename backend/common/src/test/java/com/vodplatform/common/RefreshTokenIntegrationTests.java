package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vodplatform.common.contract.ApiContract;
import com.vodplatform.common.contract.ContractValidation;
import com.vodplatform.common.support.CookieAuthFixture;
import com.vodplatform.common.support.PostgresTestDatabase;
import jakarta.servlet.http.Cookie;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;

@SpringBootTest(properties = {
        "auth.tokens.secret=test-only-secret-with-at-least-32-bytes",
        "CORS_ALLOWED_ORIGINS=http://localhost"
})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class RefreshTokenIntegrationTests extends CookieAuthFixture implements PostgresTestDatabase {
    @Test
    void cookieAttributesFollowTheAbsoluteSessionExpiryAcrossRotation() throws Exception {
        var login = login().andExpect(status().isOk()).andExpect(jsonPath("$.refreshToken").doesNotExist()).andReturn();
        String header = login.getResponse().getHeader("Set-Cookie");
        HttpCookie attributes = HttpCookie.parse(header).getFirst();
        assertThat(attributes.isHttpOnly()).isTrue();
        assertThat(attributes.getSecure()).isTrue();
        assertThat(attributes.getDomain()).isNull();
        assertThat(attributes.getPath()).isEqualTo("/api/v1/auth");
        assertThat(header.contains("SameSite=Strict")).isTrue();
        Instant expiry = jdbc.queryForObject("select expires_at from refresh_tokens where user_id=?",
                Timestamp.class, user).toInstant();
        assertThat(expiry).isEqualTo(now.plus(Duration.ofDays(7)));
        assertThat(attributes.getMaxAge()).isEqualTo(Duration.between(now, expiry).getSeconds());
        when(clock.instant()).thenReturn(now.plusSeconds(100));
        var rotated = refresh(cookie(login)).andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").doesNotExist()).andReturn();
        assertThat(cookie(rotated).getValue().equals(cookie(login).getValue())).isFalse();
        assertThat(revoked()).isEqualTo(1);
        String rotatedCookieHeader = rotated.getResponse().getHeader("Set-Cookie");
        assertThat(HttpCookie.parse(rotatedCookieHeader).getFirst().getMaxAge()).isEqualTo(604700);
        assertThat(jdbc.queryForObject("select count(distinct session_id) from refresh_tokens where user_id=?",
                Integer.class, user)).isEqualTo(1);
        assertThat(jdbc.queryForList("select expires_at from refresh_tokens where user_id=?", Timestamp.class, user))
                .containsOnly(Timestamp.from(expiry));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "unknown", "expired", "revoked", "over-long", "inactive", "blank", "legacy"})
    void invalidCookieReturns401AndClearsWithoutLeakingIt(String kind) throws Exception {
        Cookie original = issued();
        if (kind.equals("expired")) {
            jdbc.update("update refresh_tokens set expires_at=? where user_id=?",
                    Timestamp.from(now.minusSeconds(1)), user);
        }
        if (kind.equals("revoked")) {
            jdbc.update("update refresh_tokens set revoked_at=? where user_id=?", Timestamp.from(now), user);
        }
        if (kind.equals("inactive")) {
            jdbc.update("update users set status='DISABLED' where id=?", user);
        }
        if (kind.equals("legacy")) {
            jdbc.update("update refresh_tokens set session_id=null where user_id=?", user);
        }
        Cookie[] cookies = switch (kind) {
            case "missing" -> new Cookie[0];
            case "unknown" -> new Cookie[] {new Cookie("vod_refresh", "unknown-cookie")};
            case "over-long" -> new Cookie[] {new Cookie("vod_refresh", "A".repeat(129))};
            case "blank" -> new Cookie[] {new Cookie("vod_refresh", "")};
            default -> new Cookie[] {original};
        };
        var result = refresh(cookies).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED")).andReturn();
        assertThat(HttpCookie.parse(result.getResponse().getHeader("Set-Cookie")).getFirst().getMaxAge()).isZero();
    }

    @Test
    void duplicateCookiesAreRejectedWithoutRevocationOrRotation() throws Exception {
        Cookie token = issued();
        refresh(token, new Cookie("vod_refresh", "another-cookie")).andExpect(status().isUnauthorized());
        assertThat(revoked()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id=?", Integer.class, user))
                .isEqualTo(1);
    }

    @Test
    void replayCommitsFamilyRevocationAndInvalidatesTheWarmBearerCache() throws Exception {
        var login = login().andExpect(status().isOk()).andReturn();
        Cookie original = cookie(login);
        var profile = get("/api/v1/users/me").header("Authorization", bearer(login));
        mvc.perform(profile).andExpect(status().isOk());
        Cookie successor = cookie(refresh(original).andExpect(status().isOk()).andReturn());
        refresh(original).andExpect(status().isUnauthorized()).andExpect(header().exists("Set-Cookie"));
        assertThat(revokedSessions()).isEqualTo(1);
        refresh(successor).andExpect(status().isUnauthorized());
        mvc.perform(profile).andExpect(status().isUnauthorized());
    }

    @Test
    void bodyTokenIsNeverConsumed() throws Exception {
        Cookie token = issued();
        mvc.perform(post("/api/v1/auth/refresh").header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("refreshToken", token.getValue()))))
                .andExpect(status().isUnauthorized());
        assertThat(revoked()).isZero();
    }

    @Test
    void databaseFailureIs503AndDoesNotClearTheCookie() throws Exception {
        Cookie token = issued();
        doThrow(new DataAccessResourceFailureException("fixture")).when(tokens).findByTokenHash(anyString());
        refresh(token).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_STATE_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void concurrentRefreshesHaveExactlyOneWinnerOnPostgres() throws Exception {
        Cookie original = issued();
        var pool = Executors.newFixedThreadPool(2);
        var barrier = new CyclicBarrier(2);
        try {
            Callable<Integer> refreshRequest = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return refresh(original).andReturn().getResponse().getStatus();
            };
            var firstRefresh = pool.submit(refreshRequest);
            var secondRefresh = pool.submit(refreshRequest);
            assertThat(List.of(firstRefresh.get(20, TimeUnit.SECONDS), secondRefresh.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 401);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(revoked()).isEqualTo(1);
        assertThat(revokedSessions()).isEqualTo(1);
    }
    @Test
    void originAndFetchSiteMatrixAlwaysUsesJsonErrors() throws Exception {
        for (String path : List.of("login", "register", "refresh", "logout",
                "%6cogin", "reg%69ster", "%72efresh", "logou%74")) {
            String endpoint = URLDecoder.decode(path, StandardCharsets.UTF_8);
            for (String origin : List.of("absent", ORIGIN, "https://untrusted.example", "null")) {
                for (String site : List.of("absent", "same-origin", "same-site", "cross-site")) {
                    var request = post(URI.create("/api/v1/auth/" + path))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}");
                    if (!path.equals(endpoint)) {
                        request.with(ContractValidation.offContract(
                                "Encoded auth alias; validate the response against its canonical operation below"));
                    }
                    if (!origin.equals("absent")) {
                        request.header("Origin", origin);
                    }
                    if (!site.equals("absent")) {
                        request.header("Sec-Fetch-Site", site);
                    }
                    boolean originRequired = endpoint.equals("refresh") || endpoint.equals("logout");
                    boolean rejected = site.equals("cross-site")
                            || (!origin.equals("absent") && !origin.equals(ORIGIN))
                            || (originRequired && origin.equals("absent"));
                    var result = mvc.perform(request);
                    if (rejected) {
                        result.andExpect(status().isForbidden())
                                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                                .andExpect(jsonPath("$.code").value("ORIGIN_NOT_ALLOWED"));
                    } else {
                        int expectedStatus = switch (endpoint) {
                            case "logout" -> 204;
                            case "refresh" -> 401;
                            default -> 400;
                        };
                        result.andExpect(status().is(expectedStatus));
                    }
                    if (!path.equals(endpoint)) {
                        result.andExpect(actual -> {
                            var response = actual.getResponse();
                            assertThat(ApiContract.get().violations(
                                    "POST", "/api/v1/auth/" + endpoint, response.getStatus(),
                                    response.getContentType(), response.getContentAsByteArray())).isEmpty();
                        });
                    }
                }
            }
        }
    }
}
