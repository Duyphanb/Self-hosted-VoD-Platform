package com.vodplatform.worker.storage;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vodplatform.worker.transcode.HlsOutputValidator;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
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

    @Test
    void publishesThePlaylistAndAllSegmentsToIndependentAttemptPrefixes(@TempDir Path temp) throws Exception {
        Path output = temp.toRealPath();
        Path segments = Files.createDirectory(output.resolve("segments"));
        Path first = Files.writeString(segments.resolve("seg_00001.ts"), "attempt one");
        Files.writeString(segments.resolve("seg_00002.ts"), "second segment");
        String playlist = """
                #EXTM3U
                #EXT-X-PLAYLIST-TYPE:VOD
                #EXTINF:1,
                segments/seg_00001.ts
                #EXTINF:1,
                segments/seg_00002.ts
                #EXT-X-ENDLIST
                """;
        Files.writeString(output.resolve("master.m3u8"), playlist);
        UUID asset = UUID.randomUUID();

        try (var backend = MinioClient.builder().endpoint(System.getenv("MINIO_ENDPOINT"))
                     .credentials(System.getenv("MINIO_BACKEND_ACCESS_KEY"), System.getenv("MINIO_BACKEND_SECRET_KEY"))
                     .region("us-east-1").build();
                var context = new AnnotationConfigApplicationContext(StorageConfiguration.class)) {
            var uploader = context.getBean(HlsUploadService.class);
            var firstUpload = uploader.upload(asset, 1, HlsOutputValidator.validate(output, Duration.ofSeconds(2)));
            Files.writeString(first, "attempt ten");
            var tenthUpload = uploader.upload(asset, 10, HlsOutputValidator.validate(output, Duration.ofSeconds(2)));

            String one = "video-assets/" + asset + "/attempt-1/";
            String ten = "video-assets/" + asset + "/attempt-10/";
            String bucket = System.getenv("MINIO_BUCKET_HLS");
            assertEquals(new HlsUploadService.UploadedHls(bucket, one + "master.m3u8"), firstUpload);
            assertEquals(new HlsUploadService.UploadedHls(bucket, ten + "master.m3u8"), tenthUpload);
            assertObject(backend, bucket, one + "master.m3u8", playlist, "application/vnd.apple.mpegurl");
            assertObject(backend, bucket, ten + "master.m3u8", playlist, "application/vnd.apple.mpegurl");
            assertObject(backend, bucket, one + "segments/seg_00001.ts", "attempt one", "video/mp2t");
            assertObject(backend, bucket, ten + "segments/seg_00001.ts", "attempt ten", "video/mp2t");
            assertObject(backend, bucket, one + "segments/seg_00002.ts", "second segment", "video/mp2t");
            assertObject(backend, bucket, ten + "segments/seg_00002.ts", "second segment", "video/mp2t");
        }
    }

    private static void assertObject(MinioClient client, String bucket, String key, String value, String contentType)
            throws Exception {
        try (var object = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            assertEquals(contentType, object.headers().get("Content-Type"));
            assertArrayEquals(value.getBytes(UTF_8), object.readAllBytes());
        }
    }
}
