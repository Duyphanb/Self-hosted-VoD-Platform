package com.vodplatform.common.storage;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class StorageMultipartConcurrencyTests {
    @Test
    void fortyFailingMultipartPutsAbortAndDrainWithoutWedgingTheSharedProductionClient() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            try (var s3 = new FakeS3()) {
                var properties = StorageConfigurationTests.properties(s3.endpoint(), 10000, 3000, 3000);
                var config = new StorageConfiguration();
                try (var client = config.minioClient(properties)) {
                    var storage = config.objectStorage(client, properties);
                    var start = new CountDownLatch(1);
                    // Exceed the configured 32-per-host limit: raising it alone cannot pass this test.
                    var futures = java.util.stream.IntStream.range(0, 40).mapToObj(i -> executor.submit(() -> {
                        assertTrue(start.await(2, TimeUnit.SECONDS));
                        StorageConfigurationTests.assertSafe(assertThrows(IOException.class,
                                () -> storage.put("vod-raw", "failed-" + i,
                                        new ZeroStream(6L * 1024 * 1024), 6L * 1024 * 1024, "video/mp4")), "PUT");
                        return null;
                    })).toList();
                    start.countDown();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                    for (var future : futures) future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    assertEquals(40, s3.created.size(), "Every PUT must have reached multipart creation");
                    assertEquals(s3.created, s3.aborted, "Every failed upload must be aborted");
                    try (InputStream read = storage.get("vod-hls", "follow-up")) {
                        assertArrayEquals("ok".getBytes(StandardCharsets.UTF_8), read.readAllBytes());
                    }
                    var dispatcher = StorageConfigurationTests.transport(client).dispatcher();
                    assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                        while (dispatcher.runningCallsCount() != 0) Thread.sleep(10);
                    });
                    assertEquals(0, dispatcher.queuedCallsCount());
                }
            } finally { executor.shutdownNow(); }
        });
    }

    /** Minimal S3 HTTP boundary: no Docker, credential logging or whole-object buffers. */
    private static final class FakeS3 implements AutoCloseable {
        private final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final Set<String> created = ConcurrentHashMap.newKeySet();
        private final Set<String> aborted = ConcurrentHashMap.newKeySet();

        FakeS3() throws IOException {
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
        }

        String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                String method = exchange.getRequestMethod();
                String query = exchange.getRequestURI().getRawQuery();
                if (method.equals("POST") && query != null && query.contains("uploads")) {
                    String id = UUID.randomUUID().toString();
                    created.add(id);
                    respond(exchange, 200, "<InitiateMultipartUploadResult><Bucket>vod-raw</Bucket><Key>key</Key>"
                            + "<UploadId>" + id + "</UploadId></InitiateMultipartUploadResult>", "application/xml");
                } else if (method.equals("PUT")) {
                    exchange.getRequestBody().transferTo(java.io.OutputStream.nullOutputStream());
                    try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    respond(exchange, 403, "<Error><Code>AccessDenied</Code><Message>denied</Message>"
                            + "<Resource>/vod-raw/key</Resource><RequestId>fake</RequestId></Error>", "application/xml");
                } else if (method.equals("DELETE")) {
                    for (String pair : query.split("&")) {
                        if (pair.startsWith("uploadId=")) aborted.add(pair.substring("uploadId=".length()));
                    }
                    exchange.sendResponseHeaders(204, -1);
                } else if (method.equals("GET")) {
                    respond(exchange, 200, "ok", "application/octet-stream");
                } else {
                    respond(exchange, 500, "unexpected request", "text/plain");
                }
            }
        }

        private static void respond(HttpExchange exchange, int status, String body, String type) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        @Override public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static final class ZeroStream extends InputStream {
        private long remaining;
        ZeroStream(long size) { remaining = size; }
        @Override public int read() { if (remaining == 0) return -1; remaining--; return 0; }
        @Override public int read(byte[] target, int offset, int length) {
            if (length == 0) return 0;
            if (remaining == 0) return -1;
            int count = (int) Math.min(length, remaining);
            java.util.Arrays.fill(target, offset, offset + count, (byte) 0);
            remaining -= count;
            return count;
        }
    }
}
