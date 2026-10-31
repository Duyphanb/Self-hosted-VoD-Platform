package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.vodplatform.common.health.HealthResponse;
import com.vodplatform.common.storage.StorageHealthIndicator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.health.contributor.Health;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "auth.tokens.secret=test-only-secret-with-at-least-32-bytes"
)
@AutoConfigureRestTestClient
class VodBackendApplicationTests {

    @MockitoBean
    private StorageHealthIndicator storageHealthIndicator;

    @BeforeEach
    void storageReadinessIsAvailableForHttpHealthTests() {
        when(storageHealthIndicator.health()).thenReturn(Health.up().build());
    }

    private final RestTestClient restTestClient;
    private final int port;

    @Autowired
    VodBackendApplicationTests(RestTestClient restTestClient, @LocalServerPort int port) {
        this.restTestClient = restTestClient;
        this.port = port;
    }

    @Test
    void actuatorHealthReturnsOk() {
        EntityExchangeResult<String> response = restTestClient.get()
                .uri("http://localhost:%d/actuator/health".formatted(port))
                .exchange()
                .expectBody(String.class)
                .returnResult();

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void publicHealthReturnsExpectedPayload() {
        EntityExchangeResult<HealthResponse> response = restTestClient.get()
                .uri("http://localhost:%d/api/v1/health".formatted(port))
                .exchange()
                .expectBody(HealthResponse.class)
                .returnResult();

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(response.getResponseBody()).isEqualTo(new HealthResponse("UP"));
    }
}
