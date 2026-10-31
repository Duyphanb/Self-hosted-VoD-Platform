package com.vodplatform.common.storage;

import static org.junit.jupiter.api.Assertions.*;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.MinioAsyncClient;
import io.minio.Http;
import io.minio.ListMultipartUploadsArgs;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.io.InputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.health.contributor.Status;
import org.springframework.test.util.ReflectionTestUtils;

/** Opt-in only: the caller provisions and destroys a disposable Silo project. */
@EnabledIfEnvironmentVariable(named = "MINIO_TEST_ENDPOINT", matches = "http://127\\.0\\.0\\.1:[0-9]+")
class StorageRuntimeTests {
    private MinioClient client(String role) {
        return new StorageConfiguration().minioClient(properties(role));
    }

    private StorageProperties properties(String role) {
        return new StorageProperties(System.getenv("MINIO_TEST_ENDPOINT"),
                System.getenv("MINIO_TEST_" + role + "_ACCESS_KEY"),
                System.getenv("MINIO_TEST_" + role + "_SECRET_KEY"), 10000, 60000, 60000, "vod-hls");
    }

    @Test
    void streamsLargerThanHeapRoundTripAndScopedPermissionsAreEnforced() throws Exception {
        try (MinioClient backendClient = client("BACKEND"); MinioClient workerClient = client("WORKER")) {
            var config = new StorageConfiguration();
            var backend = config.objectStorage(backendClient, properties("BACKEND"));
            var worker = config.objectStorage(workerClient, properties("WORKER"));
            String raw = UUID.randomUUID() + "/" + UUID.randomUUID() + ".mp4";
            String hls = "video-assets/" + UUID.randomUUID() + "/attempt-1/segments/00001.ts";
            long size = 128L * 1024 * 1024 + 17; // Run this test with -Xmx64m; never materialize it.
            assertTrue(Runtime.getRuntime().maxMemory() < size,
                    "The SDK smoke must run with a heap smaller than the generated object");
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

    @Test
    void bothScopedIdentitiesAbortAfterAConfirmedPartAndLeaveNoIncompleteUpload() throws Exception {
        try (MinioClient rootClient = client("ROOT")) {
            // The SDK's low-level audit APIs live on its async client. Reuse the shipped client config.
            var rootAsync = (MinioAsyncClient) ReflectionTestUtils.getField(rootClient, "asyncClient");
            assertNotNull(rootAsync);
            var audit = new RootMultipartAudit(rootAsync);
            for (String role : new String[] {"BACKEND", "WORKER"}) {
                String bucket = role.equals("BACKEND") ? "vod-raw" : "vod-hls";
                String key = role.equals("BACKEND") ? UUID.randomUUID() + "/abort.mp4"
                        : "video-assets/" + UUID.randomUUID() + "/attempt-1/abort.ts";
                try (MinioClient scopedClient = client(role)) {
                    var storage = new StorageConfiguration().objectStorage(scopedClient, properties(role));
                    var source = new FailingStream(() -> {
                        var uploads = audited("ListMultipartUploads", () -> audit.uploads(bucket, key))
                                .getElementsByTagNameNS("*", "Upload");
                        assertEquals(1, uploads.getLength(), "A multipart upload must exist before injecting failure");
                        String uploadId = ((Element) uploads.item(0)).getElementsByTagNameNS("*", "UploadId")
                                .item(0).getTextContent();
                        var parts = audited("ListParts", () -> audit.parts(bucket, key, uploadId))
                                .getElementsByTagNameNS("*", "Part");
                        assertTrue(parts.getLength() >= 1, "At least one uploaded part must be confirmed before failure");
                    });
                    StorageConfigurationTests.assertSafe(assertThrows(IOException.class,
                            () -> storage.put(bucket, key, source, 16L * 1024 * 1024, "video/mp4")), "PUT");
                    assertTrue(source.failureInjected, "The source must reach the failure after 7 MiB");
                    assertEquals(0, audited("ListMultipartUploads after abort", () -> audit.uploads(bucket, key))
                            .getElementsByTagNameNS("*", "Upload").getLength(),
                            "SDK abort must leave no incomplete upload under the exact test key");
                }
            }
        }
    }

    @Test
    void liveReadinessIsUpForScopedCredentialsAndDownForWrongSecret() throws Exception {
        var config = new StorageConfiguration();
        var valid = properties("BACKEND");
        var invalid = new StorageProperties(valid.endpoint(), valid.accessKey(), UUID.randomUUID().toString(),
                valid.connectTimeoutMs(), valid.readTimeoutMs(), valid.writeTimeoutMs(), valid.hlsBucket());
        try (var probe = config.storageHealthIndicator(valid); var wrongSecret = config.storageHealthIndicator(invalid)) {
            assertEquals(Status.UP, probe.health().getStatus());
            assertEquals(Status.DOWN, wrongSecret.health().getStatus());
        }
    }

    @FunctionalInterface
    private interface Audit { void verify() throws Exception; }

    @FunctionalInterface
    private interface AuditCall<T> { T run() throws Exception; }

    private static <T> T audited(String stage, AuditCall<T> call) {
        try {
            return call.run();
        } catch (Exception exception) {
            // Keep enough type information to diagnose the audit itself, without SDK payloads or causes.
            StringBuilder types = new StringBuilder();
            for (Throwable cause = exception; cause != null && types.length() < 600; cause = cause.getCause()) {
                if (!types.isEmpty()) types.append(" -> ");
                types.append(cause.getClass().getSimpleName());
            }
            return fail("Root " + stage + " audit failed (" + types + ")");
        }
    }

    /** Root is used only to inspect multipart state, never for application PUT/GET/DELETE. */
    private static final class RootMultipartAudit extends MinioAsyncClient {
        RootMultipartAudit(MinioAsyncClient client) { super(client); }

        Document uploads(String bucket, String prefix) throws Exception {
            return xml(executeGetAsync(ListMultipartUploadsArgs.builder().bucket(bucket).prefix(prefix).build(),
                    null, new Http.QueryParameters("uploads", "", "prefix", prefix)), "ListMultipartUploadsResult");
        }

        Document parts(String bucket, String key, String uploadId) throws Exception {
            return xml(executeGetAsync(GetObjectArgs.builder().bucket(bucket).object(key).build(),
                    null, new Http.QueryParameters("uploadId", uploadId)), "ListPartsResult");
        }

        private static Document xml(java.util.concurrent.CompletableFuture<okhttp3.Response> pending,
                String expectedRoot) throws Exception {
            // SDK 9.0.3's strict ListMultipartUploads model rejects Silo's response. Inspect the raw
            // signed response with a hardened parser, retaining production transport/configuration.
            try (var response = pending.get(5, TimeUnit.SECONDS)) {
                assertEquals(200, response.code(), "Root multipart audit must return HTTP 200");
                assertNotNull(response.body(), "Root multipart audit must return XML");
                var factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                var document = factory.newDocumentBuilder().parse(response.body().byteStream());
                assertEquals(expectedRoot, document.getDocumentElement().getLocalName(),
                        "Root multipart audit must return the expected S3 result");
                return document;
            }
        }
    }

    private static final class FailingStream extends InputStream {
        private long remaining = 7L * 1024 * 1024;
        private final Audit audit;
        private boolean failureInjected;
        FailingStream(Audit audit) { this.audit = audit; }
        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            read(one, 0, 1);
            return 0;
        }
        @Override public int read(byte[] target, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (remaining == 0) {
                try { audit.verify(); } catch (Exception exception) {
                    fail("Root multipart audit failed (" + exception.getClass().getSimpleName() + ")");
                }
                failureInjected = true;
                throw new IOException("Injected source failure");
            }
            int count = (int) Math.min(length, remaining);
            java.util.Arrays.fill(target, offset, offset + count, (byte) 0);
            remaining -= count;
            return count;
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
