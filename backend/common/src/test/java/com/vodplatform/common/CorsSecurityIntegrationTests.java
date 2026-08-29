package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(
        properties = {
                "auth.tokens.secret=test-only-secret-with-at-least-32-bytes",
                "auth.tokens.access-token-ttl=15m",
                "auth.tokens.refresh-token-ttl=7d",
                "CORS_ALLOWED_ORIGINS=https://app.example.test"
        }
)
@AutoConfigureMockMvc
class CorsSecurityIntegrationTests {

    private static final String ALLOWED_ORIGIN = "https://app.example.test";
    private static final String DISALLOWED_ORIGIN = "https://untrusted.example.test";
    private static final List<String> ALLOWED_METHODS = List.of(
            "GET",
            "POST",
            "PUT",
            "DELETE",
            "OPTIONS"
    );

    @Autowired
    private MockMvc mockMvc;

    @Test
    void allowedPreflightRunsBeforeAuthenticationForPublicProtectedAndHlsPaths() throws Exception {
        assertAllowedPreflight(
                "/api/v1/auth/login",
                "POST",
                List.of("Content-Type", "X-Request-ID")
        );
        assertAllowedPreflight(
                "/api/v1/users/me",
                "GET",
                List.of("Authorization")
        );
        assertAllowedPreflight(
                "/hls/video-assets/00000000-0000-0000-0000-000000000000/master.m3u8",
                "GET",
                List.of("Authorization", "X-Request-ID")
        );
    }

    @Test
    void disallowedOriginMethodAndHeaderReceiveNoCorsAuthorization() throws Exception {
        assertRejectedPreflight(
                "/api/v1/auth/login",
                DISALLOWED_ORIGIN,
                "POST",
                "Content-Type"
        );
        assertRejectedPreflight(
                "/api/v1/auth/login",
                ALLOWED_ORIGIN,
                "PATCH",
                "Content-Type"
        );
        assertRejectedPreflight(
                "/api/v1/auth/login",
                ALLOWED_ORIGIN,
                "POST",
                "X-Unsafe-Header"
        );
    }

    @Test
    void actualCrossOriginRequestsPreservePublicAndUnauthorizedBehavior() throws Exception {
        mockMvc.perform(get("/api/v1/health")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));

        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void sameOriginAndNonCorsPathsRemainUnchanged() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

        mockMvc.perform(get("/actuator/health")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    private void assertAllowedPreflight(
            String path,
            String method,
            List<String> requestedHeaders
    ) throws Exception {
        MvcResult result = mockMvc.perform(options(path)
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method)
                        .header(
                                HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                                String.join(",", requestedHeaders)
                        ))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andReturn();

        assertThat(csvHeader(result, HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS))
                .containsExactlyElementsOf(ALLOWED_METHODS);
        assertThat(csvHeader(result, HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS))
                .map(String::toLowerCase)
                .containsExactlyElementsOf(requestedHeaders.stream()
                        .map(String::toLowerCase)
                        .toList());
    }

    private void assertRejectedPreflight(
            String path,
            String origin,
            String method,
            String requestedHeaders
    ) throws Exception {
        mockMvc.perform(options(path)
                        .header(HttpHeaders.ORIGIN, origin)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, requestedHeaders))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
    }

    private List<String> csvHeader(MvcResult result, String headerName) {
        String value = result.getResponse().getHeader(headerName);
        assertThat(value).isNotNull();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .toList();
    }
}
