package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.vodplatform.common.health.HealthResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VodBackendApplicationTests {

    private final TestRestTemplate restTemplate;
    private final int port;

    @Autowired
    VodBackendApplicationTests(TestRestTemplate restTemplate, @LocalServerPort int port) {
        this.restTemplate = restTemplate;
        this.port = port;
    }

    @Test
    void actuatorHealthReturnsOk() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:%d/actuator/health".formatted(port),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void publicHealthReturnsExpectedPayload() {
        ResponseEntity<HealthResponse> response = restTemplate.getForEntity(
                "http://localhost:%d/api/v1/health".formatted(port),
                HealthResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new HealthResponse("UP"));
    }
}
