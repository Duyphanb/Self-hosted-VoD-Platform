package com.vodplatform.worker.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "RABBITMQ_HOST=127.0.0.1", "RABBITMQ_PORT=1",
        "RABBITMQ_USERNAME=test-worker", "RABBITMQ_PASSWORD=test-only-worker-secret",
        "RABBITMQ_ENCODING_QUEUE=test-encoding-queue", "WORKER_CONCURRENCY=1", "WORKER_PREFETCH=1"
})
@AutoConfigureRestTestClient
class VodWorkerApplicationTests {

    private final RestTestClient restTestClient;
    private final int port;
    private final SimpleMessageListenerContainer listener;

    @Autowired
    VodWorkerApplicationTests(RestTestClient restTestClient, @LocalServerPort int port,
            SimpleMessageListenerContainer listener) {
        this.restTestClient = restTestClient;
        this.port = port;
        this.listener = listener;
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
    void startsWithTheListenerRegisteredButStopped() {
        assertThat(listener.getQueueNames()).containsExactly("test-encoding-queue");
        assertThat(listener.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.AUTO);
        assertThat(listener.isAutoStartup()).isFalse();
        assertThat(listener.isRunning()).isFalse();
        assertThat(listener.getActiveConsumerCount()).isZero();
        assertThatThrownBy(listener::start).isInstanceOf(IllegalStateException.class)
                .hasMessage("Encoding job processor is not configured");
        assertThat(listener.isRunning()).isFalse();
    }
}
