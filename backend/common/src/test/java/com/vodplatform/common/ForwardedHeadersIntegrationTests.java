package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.vodplatform.common.support.PostgresTestDatabase;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.filter.OncePerRequestFilter;

/** HTTP, not MockMvc: Tomcat's native valve runs before this test-only request-view filter. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "auth.tokens.secret=test-only-secret-with-at-least-32-bytes", "CORS_ALLOWED_ORIGINS="})
@AutoConfigureRestTestClient
@Import(ForwardedHeadersIntegrationTests.RequestViewConfiguration.class)
class ForwardedHeadersIntegrationTests implements PostgresTestDatabase {
    @Autowired RestTestClient client;
    @Autowired TomcatServerProperties tomcatProperties;
    @LocalServerPort int port;

    @org.junit.jupiter.api.Test
    void standaloneDefaultTrustsOnlyLoopbackRatherThanPrivateRanges() throws Exception {
        assertThat(tomcatProperties.getRemoteip().getInternalProxies()).isEqualTo("127.0.0.1/32,::1/128");
        var ranges = org.apache.catalina.util.NetMaskSet.parse(tomcatProperties.getRemoteip().getInternalProxies());
        assertThat(ranges.contains("127.0.0.1")).isTrue();
        assertThat(ranges.contains("::1")).isTrue();
        for (String peer : new String[]{"10.94.250.2", "172.20.0.2", "192.168.1.2", "100.64.0.2", "fc00::2"}) {
            assertThat(ranges.contains(peer)).as("private peer %s has no standalone trust", peer).isFalse();
        }
    }

    @ParameterizedTest
    @CsvSource({"http://watch.example.test:9999", "https://watch.example.test:8088"})
    void sameHostWithDifferentPortOrSchemeIsStillCrossOrigin(String origin) {
        login("http", 8088, origin).exchange().expectStatus().isForbidden()
                .expectHeader().valueEquals("X-Test-Scheme", "http")
                .expectHeader().valueEquals("X-Test-Port", "8088")
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
    }

    @ParameterizedTest
    @CsvSource({"http,80,false", "http,8088,false", "https,443,true"})
    void nativeValveRestoresBrowserOriginAndSameOriginLoginBypassesCors(
            String scheme, int edgePort, boolean secure) {
        String origin = scheme + "://watch.example.test" + (edgePort == 8088 ? ":8088" : "");
        var result = login(scheme, edgePort, origin).exchange()
                .expectStatus().isUnauthorized() // The synthetic account does not exist; CORS accepted it.
                .expectHeader().doesNotExist("Access-Control-Allow-Origin")
                .expectHeader().doesNotExist("Strict-Transport-Security")
                .expectHeader().valueEquals("X-Test-Scheme", scheme)
                .expectHeader().valueEquals("X-Test-Host", "watch.example.test")
                .expectHeader().valueEquals("X-Test-Port", Integer.toString(edgePort))
                .expectHeader().valueEquals("X-Test-Secure", Boolean.toString(secure))
                .expectHeader().valueEquals("X-Test-Remote", "198.51.100.7")
                .expectBody(String.class).returnResult();
        assertThat(result.getResponseBody()).contains("INVALID_CREDENTIALS");

        login(scheme, edgePort, "https://untrusted.example.test").exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin")
                .expectHeader().doesNotExist("Strict-Transport-Security");
    }

    @ParameterizedTest
    @CsvSource({"/api/v1/health,200", "/api/v1/users/me,401"})
    void secureRequestsOnBothSecurityChainsDoNotEnableBackendHsts(String path, int status) {
        client.get().uri("http://127.0.0.1:" + port + path)
                .header("X-Forwarded-For", "198.51.100.7")
                .header("X-Forwarded-Host", "watch.example.test")
                .header("X-Forwarded-Port", "443")
                .header("X-Forwarded-Proto", "https")
                .exchange().expectStatus().isEqualTo(status)
                .expectHeader().valueEquals("X-Test-Secure", "true")
                .expectHeader().doesNotExist("Strict-Transport-Security");
    }

    private RestTestClient.RequestHeadersSpec<?> login(String scheme, int edgePort, String origin) {
        return client.post().uri("http://127.0.0.1:" + port + "/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Origin", origin)
                .header("X-Forwarded-For", "198.51.100.7")
                .header("X-Forwarded-Host", "watch.example.test")
                .header("X-Forwarded-Port", Integer.toString(edgePort))
                .header("X-Forwarded-Proto", scheme)
                // The native valve does not read RFC Forwarded, even from an internal peer.
                .header("Forwarded", "for=203.0.113.9;proto=https;host=forged.example.test:9999")
                .body("{\"email\":\"forwarded-missing@example.test\",\"password\":\"wrong-password\"}");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RequestViewConfiguration {
        @Bean
        FilterRegistrationBean<OncePerRequestFilter> requestViewFilter() {
            var registration = new FilterRegistrationBean<OncePerRequestFilter>(new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                FilterChain chain) throws ServletException, IOException {
                    response.setHeader("X-Test-Scheme", request.getScheme());
                    response.setHeader("X-Test-Host", request.getServerName());
                    response.setHeader("X-Test-Port", Integer.toString(request.getServerPort()));
                    response.setHeader("X-Test-Secure", Boolean.toString(request.isSecure()));
                    response.setHeader("X-Test-Remote", request.getRemoteAddr());
                    chain.doFilter(request, response);
                }
            });
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
    }
}
