package com.vodplatform.worker.queue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VodWorkerApplicationTests {

    private final TestRestTemplate restTemplate;
    private final int port;

    @Autowired
    VodWorkerApplicationTests(TestRestTemplate restTemplate, @LocalServerPort int port) {
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
}
