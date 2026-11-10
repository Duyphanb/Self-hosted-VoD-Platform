package com.vodplatform.worker.storage;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

@EnabledIfEnvironmentVariable(named = "WORKER_DEPENDENCY_SMOKE", matches = "true")
@Timeout(30)
class WorkerObjectStorageSiloTests {
    @Test
    void readsRawMetadataAndBytesAndWritesHlsWithScopedCredentials() throws Exception {
        String rawBucket = System.getenv("MINIO_BUCKET_RAW");
        String hlsBucket = System.getenv("MINIO_BUCKET_HLS");
        String assetPrefix = "video-assets/" + UUID.randomUUID() + "/";
        String rawKey = assetPrefix + "source.mp4";
        String hlsKey = assetPrefix + "attempt-1/master.m3u8";
        byte[] raw = "generated raw bytes".getBytes(UTF_8);
        byte[] hls = "#EXTM3U\n#EXT-X-ENDLIST\n".getBytes(UTF_8);
        String contentType = "application/vnd.apple.mpegurl";

        try (var backend = MinioClient.builder().endpoint(System.getenv("MINIO_ENDPOINT"))
                     .credentials(System.getenv("MINIO_BACKEND_ACCESS_KEY"), System.getenv("MINIO_BACKEND_SECRET_KEY"))
                     .region("us-east-1").build();
                var context = new AnnotationConfigApplicationContext(StorageConfiguration.class)) {
            backend.putObject(PutObjectArgs.builder().bucket(rawBucket).object(rawKey)
                    .stream(new ByteArrayInputStream(raw), (long) raw.length, -1L).build());
            var storage = context.getBean(WorkerObjectStorage.class);
            assertEquals(Status.UP, context.getBean("workerStorage", HealthIndicator.class).health().getStatus());
            assertEquals(raw.length, storage.rawSize(rawKey));
            var output = new ByteArrayOutputStream();
            assertEquals(raw.length, storage.downloadRaw(rawKey, output));
            assertArrayEquals(raw, output.toByteArray());

            storage.putHls(hlsKey, new ByteArrayInputStream(hls), hls.length, contentType);
            var get = GetObjectArgs.builder().bucket(hlsBucket).object(hlsKey).build();
            try (var readBack = backend.getObject(get)) {
                assertEquals(contentType, readBack.headers().get("Content-Type"));
                assertArrayEquals(hls, readBack.readAllBytes());
            }
            MinioClient worker = context.getBean(MinioClient.class);
            assertEquals("AccessDenied", assertThrows(ErrorResponseException.class,
                    () -> worker.getObject(get).close()).errorResponse().code());
            assertEquals("AccessDenied", assertThrows(ErrorResponseException.class,
                    () -> worker.putObject(PutObjectArgs.builder().bucket(rawBucket).object(rawKey)
                            .stream(new ByteArrayInputStream(raw), (long) raw.length, -1L).build())).errorResponse().code());
        }
    }

    @Test
    void healthRefusesIncorrectWorkerCredentials() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("bad-worker-password",
                    Map.of("MINIO_SECRET_KEY", "invalid-disposable-worker-secret")));
            context.register(StorageConfiguration.class);
            context.refresh();
            assertEquals(Status.DOWN, context.getBean("workerStorage", HealthIndicator.class).health().getStatus());
        }
    }
}
