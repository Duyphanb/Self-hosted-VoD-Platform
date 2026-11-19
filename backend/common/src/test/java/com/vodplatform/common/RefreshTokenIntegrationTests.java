package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.vodplatform.common.support.CookieAuthFixture;
import com.vodplatform.common.support.PostgresTestDatabase;
import jakarta.servlet.http.Cookie;
import java.net.HttpCookie;
import java.time.Instant;
import java.util.List;
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

@SpringBootTest(properties={"auth.tokens.secret=test-only-secret-with-at-least-32-bytes",
        "CORS_ALLOWED_ORIGINS=http://localhost"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class RefreshTokenIntegrationTests extends CookieAuthFixture implements PostgresTestDatabase {
    @Test void cookieAttributesFollowStoredExpiryAndRotationKeepsSlidingLifetime() throws Exception {
        var login = login().andExpect(status().isOk()).andExpect(jsonPath("$.refreshToken").doesNotExist()).andReturn();
        String header = login.getResponse().getHeader("Set-Cookie");
        HttpCookie attributes = HttpCookie.parse(header).getFirst();
        assertThat(attributes.isHttpOnly()).isTrue(); assertThat(attributes.getSecure()).isTrue();
        assertThat(attributes.getDomain()).isNull(); assertThat(attributes.getPath()).isEqualTo("/api/v1/auth");
        assertThat(header.contains("SameSite=Strict")).isTrue();
        Instant expiry = jdbc.queryForObject("select expires_at from refresh_tokens where user_id=?", java.sql.Timestamp.class, user).toInstant();
        assertThat(attributes.getMaxAge()).isEqualTo(java.time.Duration.between(now, expiry).getSeconds());
        org.mockito.Mockito.when(clock.instant()).thenReturn(now.plusSeconds(100));
        var rotated = refresh(cookie(login)).andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").doesNotExist()).andReturn();
        assertThat(cookie(rotated).getValue().equals(cookie(login).getValue())).isFalse();
        assertThat(revoked()).isEqualTo(1);
        assertThat(HttpCookie.parse(rotated.getResponse().getHeader("Set-Cookie")).getFirst().getMaxAge()).isEqualTo(604800);
    }

    @ParameterizedTest @ValueSource(strings={"missing","unknown","expired","revoked","over-long","inactive","blank"})
    void invalidCookieReturns401AndClearsWithoutLeakingIt(String kind) throws Exception {
        Cookie original = issued();
        if (kind.equals("expired")) jdbc.update("update refresh_tokens set expires_at=? where user_id=?", java.sql.Timestamp.from(now.minusSeconds(1)), user);
        if (kind.equals("revoked")) jdbc.update("update refresh_tokens set revoked_at=? where user_id=?", java.sql.Timestamp.from(now), user);
        if (kind.equals("inactive")) jdbc.update("update users set status='DISABLED' where id=?", user);
        Cookie[] cookies = switch(kind) {
            case "missing" -> new Cookie[0];
            case "unknown" -> new Cookie[]{new Cookie("vod_refresh","unknown-cookie")};
            case "over-long" -> new Cookie[]{new Cookie("vod_refresh","A".repeat(129))};
            case "blank" -> new Cookie[]{new Cookie("vod_refresh","")};
            default -> new Cookie[]{original};
        };
        var result = refresh(cookies).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED")).andReturn();
        assertThat(HttpCookie.parse(result.getResponse().getHeader("Set-Cookie")).getFirst().getMaxAge()).isZero();
    }

    @Test void duplicateCookiesAreRejectedWithoutRevocationOrRotation() throws Exception {
        Cookie token = issued();
        refresh(token, new Cookie("vod_refresh", "another-cookie")).andExpect(status().isUnauthorized());
        assertThat(revoked()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id=?", Integer.class,user)).isEqualTo(1);
    }
    @Test void replayClearsCookieButDoesNotRevokeTheSuccessor() throws Exception {
        Cookie original = issued();
        Cookie successor = cookie(refresh(original).andExpect(status().isOk()).andReturn());
        refresh(original).andExpect(status().isUnauthorized());
        refresh(successor).andExpect(status().isOk());
    }
    @Test void bodyTokenIsNeverConsumed() throws Exception {
        Cookie token = issued();
        mvc.perform(post("/api/v1/auth/refresh").header("Origin",ORIGIN).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("refreshToken",token.getValue()))))
                .andExpect(status().isUnauthorized());
        assertThat(revoked()).isZero();
    }
    @Test void databaseFailureIs503AndDoesNotClearTheCookie() throws Exception {
        Cookie token = issued();
        doThrow(new DataAccessResourceFailureException("fixture")).when(tokens).findByTokenHash(anyString());
        refresh(token).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_STATE_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }
    @Test void concurrentRefreshesHaveExactlyOneWinnerOnPostgres() throws Exception {
        Cookie original = issued();
        var pool = Executors.newFixedThreadPool(2);
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        try {
            var work = (java.util.concurrent.Callable<Integer>) () -> {
                barrier.await(10,TimeUnit.SECONDS);
                return refresh(original).andReturn().getResponse().getStatus();
            };
            var a=pool.submit(work); var b=pool.submit(work);
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,401);
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue(); }
        assertThat(revoked()).isEqualTo(1);
    }
    @Test void originAndFetchSiteMatrixAlwaysUsesJsonErrors() throws Exception {
        for(String endpoint:List.of("login","register","refresh","logout")) {
            for(String origin:List.of("absent",ORIGIN,"https://untrusted.example","null")) {
                for(String site:List.of("absent","same-origin","same-site","cross-site")) {
                    var request=post("/api/v1/auth/"+endpoint).contentType(MediaType.APPLICATION_JSON).content("{}");
                    if(!origin.equals("absent")) request.header("Origin",origin);
                    if(!site.equals("absent")) request.header("Sec-Fetch-Site",site);
                    boolean required=endpoint.equals("refresh")||endpoint.equals("logout");
                    boolean reject=site.equals("cross-site")||(!origin.equals("absent")&&!origin.equals(ORIGIN))
                            ||(required&&origin.equals("absent"));
                    var result=mvc.perform(request);
                    if(reject) result.andExpect(status().isForbidden()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                            .andExpect(jsonPath("$.code").value("ORIGIN_NOT_ALLOWED"));
                    else result.andExpect(status().is(endpoint.equals("logout")?204:endpoint.equals("refresh")?401:400));
                }
            }
        }
    }
}
