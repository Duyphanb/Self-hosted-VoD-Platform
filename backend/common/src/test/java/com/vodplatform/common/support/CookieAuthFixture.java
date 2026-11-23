package com.vodplatform.common.support;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vodplatform.auth.persistence.RefreshTokenRepository;
import jakarta.servlet.http.Cookie;
import java.net.HttpCookie;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/** Committed fixtures owned by one test; no table-wide cleanup or persistent token output. */
public abstract class CookieAuthFixture {
    protected static final String ORIGIN = "http://localhost";
    protected static final String PASSWORD = "fixture-password";
    protected final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected ObjectMapper mapper;
    @MockitoBean
    protected Clock clock;
    @MockitoSpyBean
    protected RefreshTokenRepository tokens;
    private final List<UUID> users = new ArrayList<>();
    protected String email;
    protected UUID user;

    @BeforeEach
    void createUser() throws Exception {
        when(clock.instant()).thenReturn(now);
        email = UUID.randomUUID() + "@example.com";
        var result = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", email,
                        "password", PASSWORD, "displayName", "Cookie fixture"))))
                .andExpect(status().isCreated()).andReturn();
        String userId = mapper.readTree(result.getResponse().getContentAsString()).path("id").asString();
        user = UUID.fromString(userId);
        users.add(user);
    }

    @AfterEach
    void cleanup() {
        for (UUID userId : users) {
            jdbc.update("delete from users where id=?", userId);
        }
    }

    protected ResultActions login(Cookie... cookies) throws Exception {
        var request = post("/api/v1/auth/login").header("Origin", ORIGIN);
        if (cookies.length > 0) {
            request.cookie(cookies);
        }
        return mvc.perform(request
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
                        Map.of("email", email, "password", PASSWORD))));
    }

    protected Cookie issued() throws Exception {
        return cookie(login().andExpect(status().isOk()).andReturn());
    }

    protected Cookie cookie(MvcResult result) {
        String cookieHeader = result.getResponse().getHeader("Set-Cookie");
        return new Cookie("vod_refresh", HttpCookie.parse(cookieHeader).getFirst().getValue());
    }

    protected ResultActions refresh(Cookie... cookies) throws Exception {
        var request = post("/api/v1/auth/refresh").header("Origin", ORIGIN);
        if (cookies.length > 0) {
            request.cookie(cookies);
        }
        return mvc.perform(request);
    }

    protected int revoked() {
        return jdbc.queryForObject("select count(*) from refresh_tokens where user_id=? and revoked_at is not null",
                Integer.class, user);
    }

    protected int revokedSessions() {
        return jdbc.queryForObject("select count(*) from auth_sessions where user_id=? and revoked_at is not null",
                Integer.class, user);
    }

    protected String bearer(MvcResult result) {
        return "Bearer " + mapper.readTree(result.getResponse().getContentAsByteArray())
                .path("accessToken").asString();
    }
}
