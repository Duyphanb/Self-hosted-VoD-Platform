package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vodplatform.common.support.CookieAuthFixture;
import com.vodplatform.common.support.PostgresTestDatabase;
import jakarta.servlet.http.Cookie;
import java.net.HttpCookie;
import java.sql.Timestamp;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;

@SpringBootTest(properties = {
        "auth.tokens.secret=test-only-secret-with-at-least-32-bytes",
        "CORS_ALLOWED_ORIGINS=http://localhost"
})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class LogoutIntegrationTests extends CookieAuthFixture implements PostgresTestDatabase {
    @ParameterizedTest
    @ValueSource(strings = {"missing", "valid", "revoked", "duplicate", "unknown", "body"})
    void logoutIsIdempotentClearsCookieAndIgnoresBodiesAndDuplicates(String kind) throws Exception {
        Cookie token = issued();
        if (kind.equals("revoked")) {
            jdbc.update("update auth_sessions set revoked_at=? where user_id=?", Timestamp.from(now), user);
        }
        var request = post("/api/v1/auth/logout").header("Origin", ORIGIN);
        if (kind.equals("valid") || kind.equals("revoked") || kind.equals("duplicate")) {
            request.cookie(token);
        }
        if (kind.equals("duplicate")) {
            request.cookie(new Cookie("vod_refresh", "another-cookie"));
        }
        if (kind.equals("unknown")) {
            request.cookie(new Cookie("vod_refresh", "unknown-cookie"));
        }
        if (kind.equals("body")) {
            request.contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("refreshToken", token.getValue())));
        }
        var result = mvc.perform(request).andExpect(status().isNoContent()).andReturn();
        assertThat(HttpCookie.parse(result.getResponse().getHeader("Set-Cookie")).getFirst().getMaxAge()).isZero();
        assertThat(revokedSessions()).isEqualTo(kind.equals("valid") || kind.equals("revoked") ? 1 : 0);
        mvc.perform(post("/api/v1/auth/logout").header("Origin", ORIGIN)).andExpect(status().isNoContent());
    }

    @Test
    void successfulLoginRevokesASingleValidPresentedCookie() throws Exception {
        Cookie original = issued();
        login(original).andExpect(status().isOk());
        assertThat(revokedSessions()).isEqualTo(1);
        refresh(original).andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithDuplicateCookiesRevokesNothing() throws Exception {
        Cookie original = issued();
        login(original, new Cookie("vod_refresh", "other-cookie")).andExpect(status().isOk());
        assertThat(revokedSessions()).isZero();
    }
}
