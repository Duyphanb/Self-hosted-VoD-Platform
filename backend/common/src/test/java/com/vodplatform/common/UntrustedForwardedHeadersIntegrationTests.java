package com.vodplatform.common;

import com.vodplatform.common.support.PostgresTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.RestTestClient;

/** Exclude the loopback HTTP client's peer from internal proxies to prove the native trust gate. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "auth.tokens.secret=test-only-secret-with-at-least-32-bytes", "CORS_ALLOWED_ORIGINS=",
        "server.tomcat.remoteip.internal-proxies=192.0.2.0/24"})
@AutoConfigureRestTestClient
@Import(ForwardedHeadersIntegrationTests.RequestViewConfiguration.class)
class UntrustedForwardedHeadersIntegrationTests implements PostgresTestDatabase {
    @Autowired RestTestClient client;
    @LocalServerPort int port;

    @Test
    void nativeValveIgnoresEveryForwardedValueFromAnUntrustedPeer() {
        client.get().uri("http://127.0.0.1:" + port + "/api/v1/health")
                .header("X-Forwarded-For", "198.51.100.7")
                .header("X-Forwarded-Host", "forged.example.test")
                .header("X-Forwarded-Port", "443")
                .header("X-Forwarded-Proto", "https")
                .header("Forwarded", "for=203.0.113.9;proto=https;host=forged.example.test")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Test-Scheme", "http")
                .expectHeader().valueEquals("X-Test-Host", "127.0.0.1")
                .expectHeader().valueEquals("X-Test-Port", Integer.toString(port))
                .expectHeader().valueEquals("X-Test-Secure", "false")
                .expectHeader().valueEquals("X-Test-Remote", "127.0.0.1");
    }
}
