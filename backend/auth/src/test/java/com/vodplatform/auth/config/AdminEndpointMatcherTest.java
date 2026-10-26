package com.vodplatform.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AdminEndpointMatcherTest {

    @Test
    void matchesTheAdminCatalogRoutes() {
        assertThat(isAdmin("GET", "/api/v1/admin")).isTrue();
        assertThat(isAdmin("GET", "/api/v1/admin/movies")).isTrue();
        assertThat(isAdmin("DELETE", "/api/v1/admin/genres/abc")).isTrue();
        assertThat(isAdmin("POST", "/api/v1/movies")).isTrue();
        assertThat(isAdmin("PUT", "/api/v1/movies/abc")).isTrue();
        assertThat(isAdmin("DELETE", "/api/v1/movies/abc")).isTrue();
    }

    @Test
    void terminalWildcardAlsoMatchesAnEmptySegment() {
        assertThat(isAdmin("PUT", "/api/v1/movies/")).isTrue();
        assertThat(isAdmin("DELETE", "/api/v1/movies/")).isTrue();
    }

    @Test
    void doesNotMatchReadsOrDeeperPaths() {
        assertThat(isAdmin("GET", "/api/v1/movies")).isFalse();
        assertThat(isAdmin("GET", "/api/v1/movies/abc")).isFalse();
        assertThat(isAdmin("POST", "/api/v1/movies/abc")).isFalse();
        assertThat(isAdmin("PUT", "/api/v1/movies/abc/progress")).isFalse();
        assertThat(isAdmin("DELETE", "/api/v1/movies/abc/progress")).isFalse();
        assertThat(isAdmin("PUT", "/api/v1/movies/abc/")).isFalse();
    }

    private static boolean isAdmin(String method, String uri) {
        return SecurityConfiguration.ADMIN_ENDPOINTS.matches(new MockHttpServletRequest(method, uri));
    }
}
