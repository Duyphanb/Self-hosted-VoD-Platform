package com.vodplatform.common.storage;

import static org.junit.jupiter.api.Assertions.*;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.io.InputStream;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in only: the caller provisions and destroys a disposable Silo project. */
@EnabledIfEnvironmentVariable(named = "MINIO_TEST_ENDPOINT", matches = "http://127\\.0\\.0\\.1:[0-9]+")
class StorageRuntimeTests {
    private MinioClient client(String role) {
        return MinioClient.builder().endpoint(System.getenv("MINIO_TEST_ENDPOINT"))
                .credentials(System.getenv("MINIO_TEST_" + role + "_ACCESS_KEY"),
                        System.getenv("MINIO_TEST_" + role + "_SECRET_KEY"))
                .region("us-east-1").build();
    }

    @Test
    void streamsLargerThanHeapRoundTripAndScopedPermissionsAreEnforced() throws Exception {
        try (MinioClient backendClient = client("BACKEND"); MinioClient workerClient = client("WORKER")) {
            var backend = new MinioObjectStorage(backendClient);
            var worker = new MinioObjectStorage(workerClient);
            String raw = UUID.randomUUID() + "/" + UUID.randomUUID() + ".mp4";
            String hls = "video-assets/" + UUID.randomUUID() + "/attempt-1/segments/00001.ts";
            long size = 128L * 1024 * 1024 + 17; // Run this test with -Xmx64m; never materialize it.
            try {
                try (InputStream source = new PatternStream(size)) {
                    backend.put("vod-raw", raw, source, size, "video/mp4");
                }
                try (InputStream read = worker.get("vod-raw", raw)) {
                    assertPattern(read, size);
                }
                try (InputStream source = new PatternStream(size)) {
                    worker.put("vod-hls", hls, source, size, "video/mp2t");
                }
                try (InputStream read = backend.get("vod-hls", hls)) {
                    assertPattern(read, size);
                }
                denied(() -> backendClient.getObject(GetObjectArgs.builder().bucket("vod-raw").object(raw).build()));
                denied(() -> { workerClient.removeObject(RemoveObjectArgs.builder().bucket("vod-raw").object(raw).build()); return null; });
                denied(() -> { backendClient.removeObject(RemoveObjectArgs.builder().bucket("vod-hls").object(hls).build()); return null; });
                denied(() -> workerClient.putObject(PutObjectArgs.builder().bucket("vod-hls").object("outside-attempt/file.ts")
                        .stream(new PatternStream(1), 1L, -1L).build()));
                denied(() -> backendClient.putObject(PutObjectArgs.builder().bucket("vod-thumbnails").object("forbidden")
                        .stream(new PatternStream(1), 1L, -1L).build()));
                denied(() -> workerClient.putObject(PutObjectArgs.builder().bucket("vod-raw").object("forbidden")
                        .stream(new PatternStream(1), 1L, -1L).build()));
            } finally {
                backend.delete("vod-raw", raw);
                worker.delete("vod-hls", hls);
            }
            missing(() -> workerClient.getObject(GetObjectArgs.builder().bucket("vod-raw").object(raw).build()));
            missing(() -> backendClient.getObject(GetObjectArgs.builder().bucket("vod-hls").object(hls).build()));
        }
    }

    private static void assertPattern(InputStream input, long expected) throws Exception {
        byte[] buffer = new byte[8192];
        long count = 0;
        for (int n; (n = input.read(buffer)) != -1;) {
            for (int i = 0; i < n; i++) {
                if (buffer[i] != (byte) ((count + i) % 251)) {
                    fail("Stored stream differs from generated input");
                }
            }
            count += n;
        }
        assertEquals(expected, count);
    }

    private static void denied(Operation op) { assertEquals("AccessDenied", code(op)); }
    private static void missing(Operation op) { assertEquals("NoSuchKey", code(op)); }
    private static String code(Operation op) {
        try {
            Object result = op.run();
            if (result instanceof InputStream stream) stream.close();
            return "unexpected success";
        } catch (ErrorResponseException e) {
            return e.errorResponse().code();
        } catch (Exception e) {
            return "unexpected failure type"; // Do not let SDK diagnostics leak signed headers.
        }
    }
    @FunctionalInterface
    private interface Operation { Object run() throws Exception; }

    private static final class PatternStream extends InputStream {
        private final long size;
        private long position;
        PatternStream(long size) { this.size = size; }
        @Override public int read() { return position == size ? -1 : (int) (position++ % 251); }
        @Override public int read(byte[] b, int off, int len) {
            if (len == 0) return 0;
            if (position == size) return -1;
            int count = (int) Math.min(len, size - position);
            for (int i = 0; i < count; i++) b[off + i] = (byte) (position++ % 251);
            return count;
        }
    }
}
