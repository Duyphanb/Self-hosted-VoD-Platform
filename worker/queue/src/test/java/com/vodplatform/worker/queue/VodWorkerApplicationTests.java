package com.vodplatform.worker.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.vodplatform.worker.queue.messaging.EncodingJobProcessor;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
        "MINIO_CONNECT_TIMEOUT_MS=500", "MINIO_READ_TIMEOUT_MS=500", "MINIO_WRITE_TIMEOUT_MS=500",
        "FFPROBE_PATH=ffprobe", "FFMPEG_PATH=ffmpeg", "WORKER_FFPROBE_TIMEOUT_SECONDS=5",
        "WORKER_FFMPEG_TIMEOUT_MIN_SECONDS=5", "WORKER_FFMPEG_TIMEOUT_FACTOR=2", "WORKER_FFMPEG_THREADS=1",
        "WORKER_MAX_DURATION_SECONDS=10800", "WORKER_MAX_WIDTH=1920", "WORKER_MAX_HEIGHT=1080", "WORKER_MAX_STREAMS=8",
        "HLS_SEGMENT_SECONDS=6", "HLS_TARGET_HEIGHT=720", "HLS_VIDEO_MAXRATE_KBPS=4000",
        "WORKER_OUTPUT_BUDGET_KBPS=5000", "WORKER_DISK_RESERVE_MB=1"
})
@AutoConfigureRestTestClient
class VodWorkerApplicationTests {
    @TempDir
    static Path tempDirectory;

    // This context probes unavailable dependencies. The integration fixture exercises the real startup runner.
    @MockitoBean
    private WorkerStartupRunner startupRunner;

    @DynamicPropertySource
    static void workerTempDirectory(DynamicPropertyRegistry properties) throws IOException {
        String workspace = tempDirectory.toRealPath().resolve("worker").toString();
        properties.add("WORKER_TEMP_DIR", () -> workspace);
    }

    private final RestTestClient restTestClient;
    private final int port;
    private final SimpleMessageListenerContainer listener;
    private final HealthContributorRegistry healthContributors;
    private final EncodingJobProcessor processor;

    @Autowired
    VodWorkerApplicationTests(RestTestClient restTestClient, @LocalServerPort int port,
            SimpleMessageListenerContainer listener, HealthContributorRegistry healthContributors,
            EncodingJobProcessor processor) {
        this.restTestClient = restTestClient;
        this.port = port;
        this.listener = listener;
        this.healthContributors = healthContributors;
        this.processor = processor;
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
    void listenerRequiresExplicitStartupAfterRecovery() {
        assertThat(processor).isInstanceOf(WorkerPipelineProcessor.class);
        assertThat(listener.getQueueNames()).containsExactly("test-encoding-queue");
        assertThat(listener.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.AUTO);
        assertThat(listener.isAutoStartup()).isFalse();
        assertThat(listener.isRunning()).isFalse();
        assertThat(listener.getActiveConsumerCount()).isZero();
    }
}
