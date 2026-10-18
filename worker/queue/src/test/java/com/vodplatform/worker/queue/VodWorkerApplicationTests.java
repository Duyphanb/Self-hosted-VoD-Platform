package com.vodplatform.worker.queue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class VodWorkerApplicationTests {

    private final RestTestClient restTestClient;
    private final int port;

    @Autowired
    VodWorkerApplicationTests(RestTestClient restTestClient, @LocalServerPort int port) {
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
}
