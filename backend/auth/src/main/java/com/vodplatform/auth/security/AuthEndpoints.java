package com.vodplatform.auth.security;

import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** Shared decoded-path matchers for public auth routing and its Origin checks. */
public final class AuthEndpoints {
    private static final RequestMatcher REFRESH = post("/api/v1/auth/refresh");
    private static final RequestMatcher LOGOUT = post("/api/v1/auth/logout");

    public static final RequestMatcher POSTS = new OrRequestMatcher(
            post("/api/v1/auth/register"), post("/api/v1/auth/login"), REFRESH, LOGOUT);
    public static final RequestMatcher REQUIRE_ORIGIN = new OrRequestMatcher(REFRESH, LOGOUT);

    private AuthEndpoints() {
    }

    private static RequestMatcher post(String path) {
        return PathPatternRequestMatcher.pathPattern(HttpMethod.POST, path);
    }
}
