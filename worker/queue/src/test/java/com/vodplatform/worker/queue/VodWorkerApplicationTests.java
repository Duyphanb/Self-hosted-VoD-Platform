package com.vodplatform.worker.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.RestTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "RABBITMQ_HOST=127.0.0.1", "RABBITMQ_PORT=1",
        "RABBITMQ_USERNAME=test-worker", "RABBITMQ_PASSWORD=test-only-worker-secret",
        "RABBITMQ_ENCODING_QUEUE=test-encoding-queue", "WORKER_CONCURRENCY=1", "WORKER_PREFETCH=1",
        "SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:1/test",
        "SPRING_DATASOURCE_USERNAME=test-worker", "SPRING_DATASOURCE_PASSWORD=test-only-db-secret",
        "WORKER_JDBC_CONNECT_TIMEOUT_SECONDS=1", "WORKER_JDBC_READ_TIMEOUT_SECONDS=1",
        "WORKER_JDBC_STATEMENT_TIMEOUT_MS=500",
        "MINIO_ENDPOINT=http://127.0.0.1:1", "MINIO_ACCESS_KEY=test-worker",
        "MINIO_SECRET_KEY=test-only-storage-secret", "MINIO_BUCKET_RAW=vod-raw", "MINIO_BUCKET_HLS=vod-hls",
        "MINIO_CONNECT_TIMEOUT_MS=500", "MINIO_READ_TIMEOUT_MS=500", "MINIO_WRITE_TIMEOUT_MS=500"
})
@AutoConfigureRestTestClient
class VodWorkerApplicationTests {

    private final RestTestClient restTestClient;
    private final int port;
    private final SimpleMessageListenerContainer listener;
    private final HealthContributorRegistry healthContributors;

    @Autowired
    VodWorkerApplicationTests(RestTestClient restTestClient, @LocalServerPort int port,
            SimpleMessageListenerContainer listener, HealthContributorRegistry healthContributors) {
        this.restTestClient = restTestClient;
        this.port = port;
        this.listener = listener;
        this.healthContributors = healthContributors;
    }

    @Test
    void actuatorHealthReportsUnavailableDependenciesWithoutDetails() {
        assertThat(healthContributors.getContributor("workerStorage")).isNotNull();
        assertThat(healthContributors.getContributor("workerDatabase")).isNotNull();
        restTestClient.get()
                .uri("http://localhost:%d/actuator/health".formatted(port))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.status").isEqualTo("DOWN")
                .jsonPath("$.components").doesNotHaveJsonPath()
                .jsonPath("$.details").doesNotHaveJsonPath();
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
