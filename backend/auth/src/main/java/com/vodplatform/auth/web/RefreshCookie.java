package com.vodplatform.auth.web;

import com.vodplatform.auth.config.AuthCookieProperties;
import com.vodplatform.auth.config.CorsProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** One host-only cookie; duplicates and oversized values are rejected before any hashing. */
@Component
public class RefreshCookie {
    public static final String NAME = "vod_refresh";
    private final AuthCookieProperties properties;
    private final Clock clock;

    public RefreshCookie(AuthCookieProperties properties, CorsProperties origins, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        if (!properties.secure() && (origins.allowedOrigins().isEmpty()
                || !origins.allowedOrigins().stream().allMatch(RefreshCookie::loopback))) {
            throw new IllegalArgumentException("Insecure auth cookies require non-empty loopback-only origins");
        }
    }

    private static boolean loopback(String origin) {
        String host = URI.create(origin).getHost();
        if (host.equals("localhost") || host.equals("[::1]") || host.equals("::1")) return true;
        if (!host.matches("127(?:\\.[0-9]{1,3}){3}")) return false;
        return Arrays.stream(host.split("\\.")).allMatch(part -> Integer.parseInt(part) <= 255);
    }

    public String read(HttpServletRequest request) {
        var cookies = request.getCookies();
        if (cookies == null) return null;
        var matching = Arrays.stream(cookies).filter(cookie -> NAME.equals(cookie.getName())).toList();
        if (matching.size() != 1) return null;
        String value = matching.getFirst().getValue();
        return value == null || value.isBlank() || value.length() > 128 ? null : value;
    }

    public String issued(String value, Instant expiresAt) {
        return header(value, Math.max(0, Duration.between(clock.instant(), expiresAt).getSeconds()));
    }

    public String cleared() { return header("", 0); }

    private String header(String value, long seconds) {
        return ResponseCookie.from(NAME, value).httpOnly(true).secure(properties.secure())
                .sameSite("Strict").path("/api/v1/auth").maxAge(seconds).build().toString();
    }
}
