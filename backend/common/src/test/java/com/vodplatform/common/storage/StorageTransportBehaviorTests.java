package com.vodplatform.common.storage;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StorageTransportBehaviorTests {
    @Test
    void aSteadyGetBodyCanLastLongerThanEverySocketStallBudget() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var config = new StorageConfiguration();
            try (var s3 = new FakeS3(Mode.STEADY_GET)) {
                var properties = StorageConfigurationTests.properties(s3.endpoint(), 1000, 1000, 1000);
                try (var client = config.minioClient(properties)) {
                    var storage = config.objectStorage(client, properties);
                    long started = System.nanoTime();
                    try (var input = storage.get("vod-hls", "steady")) {
                        assertEquals(24L * 4096, input.transferTo(OutputStream.nullOutputStream()));
                    }
                    assertTrue(System.nanoTime() - started > TimeUnit.MILLISECONDS.toNanos(1000),
                            "The flowing response must outlast the largest configured stall timeout");
                    assertEquals(1, s3.gets.get());
                    assertEquals(0, s3.unexpected.get());
                }
            }
        });
    }

    @Test
    void oneServiceUnavailableResponseIsRetriedAndTheGetSucceeds() throws Exception {
        var config = new StorageConfiguration();
        try (var s3 = new FakeS3(Mode.TRANSIENT_GET)) {
            var properties = StorageConfigurationTests.properties(s3.endpoint(), 5000, 5000, 5000);
            try (var client = config.minioClient(properties)) {
                var storage = config.objectStorage(client, properties);
                try (var input = storage.get("vod-hls", "retry")) {
                    assertArrayEquals("ok".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
                }
                assertEquals(2, s3.gets.get());
                assertEquals(0, s3.unexpected.get());
            }
        }
    }

    @Test
    void persistentServiceUnavailableReturnsSafeIoExceptionAfterExactlyFiveAttempts() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var config = new StorageConfiguration();
            try (var s3 = new FakeS3(Mode.PERSISTENT_GET)) {
                var properties = StorageConfigurationTests.properties(s3.endpoint(), 5000, 5000, 5000);
                try (var client = config.minioClient(properties)) {
                    var storage = config.objectStorage(client, properties);
                    StorageConfigurationTests.assertSafe(assertThrows(IOException.class,
                            () -> storage.get("vod-hls", "retry")), "GET");
                    assertEquals(5, s3.gets.get(), "The SDK status retry policy must retain its five-attempt bound");
                    assertEquals(0, s3.unexpected.get());
                }
            }
        });
    }

    @Test
    void aRetriedMultipartPartCompletesTheOriginalUploadWithoutAnAbort() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            var config = new StorageConfiguration();
            try (var s3 = new FakeS3(Mode.TRANSIENT_PART)) {
                var properties = StorageConfigurationTests.properties(s3.endpoint(), 5000, 5000, 5000);
                try (var client = config.minioClient(properties)) {
                    var storage = config.objectStorage(client, properties);
                    long size = 6L * 1024 * 1024;
                    try (var input = new ZeroStream(size)) {
                        storage.put("vod-raw", "retry-upload", input, size, "video/mp4");
                    }
                    assertEquals(1, s3.creates.get());
                    assertEquals(1, s3.completes.get());
                    assertEquals(0, s3.aborts.get());
                    assertEquals(2, s3.partAttempts.get("1").get());
                    assertEquals(1, s3.partAttempts.get("2").get());
                    assertEquals(2, s3.partAttempts.size());
                    assertEquals(0, s3.unexpected.get());
                }
            }
        });
    }

    private enum Mode { STEADY_GET, TRANSIENT_GET, PERSISTENT_GET, TRANSIENT_PART }

    private static final class FakeS3 implements AutoCloseable {
        private final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final AtomicInteger gets = new AtomicInteger();
        private final AtomicInteger creates = new AtomicInteger();
        private final AtomicInteger completes = new AtomicInteger();
        private final AtomicInteger aborts = new AtomicInteger();
        private final AtomicInteger unexpected = new AtomicInteger();
        private final Map<String, AtomicInteger> partAttempts = new ConcurrentHashMap<>();
        private final Mode mode;

        FakeS3(Mode mode) throws IOException {
            this.mode = mode;
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
        }

        String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                String method = exchange.getRequestMethod();
                String query = exchange.getRequestURI().getRawQuery();
                if (method.equals("GET") && mode != Mode.TRANSIENT_PART) {
                    int attempt = gets.incrementAndGet();
                    if (mode == Mode.STEADY_GET) steadyBody(exchange);
                    else if (mode == Mode.PERSISTENT_GET || attempt == 1) unavailable(exchange);
                    else reply(exchange, 200, "ok", "application/octet-stream");
                } else if (mode == Mode.TRANSIENT_PART && method.equals("POST")
                        && query != null && query.contains("uploads")) {
                    creates.incrementAndGet();
                    reply(exchange, 200, "<InitiateMultipartUploadResult><Bucket>vod-raw</Bucket>"
                            + "<Key>retry-upload</Key><UploadId>retry-upload-id</UploadId>"
                            + "</InitiateMultipartUploadResult>", "application/xml");
                } else if (mode == Mode.TRANSIENT_PART && method.equals("PUT")) {
                    exchange.getRequestBody().transferTo(OutputStream.nullOutputStream());
                    String part = parameter(query, "partNumber");
                    if (part == null) {
                        unexpected.incrementAndGet();
                        reply(exchange, 400, "unexpected request", "text/plain");
                        return;
                    }
                    int attempt = partAttempts.computeIfAbsent(part, ignored -> new AtomicInteger()).incrementAndGet();
                    if (part.equals("1") && attempt == 1) unavailable(exchange);
                    else {
                        exchange.getResponseHeaders().set("ETag", "\"part-" + part + "\"");
                        exchange.sendResponseHeaders(200, -1);
                    }
                } else if (mode == Mode.TRANSIENT_PART && method.equals("POST")
                        && parameter(query, "uploadId") != null) {
                    exchange.getRequestBody().transferTo(OutputStream.nullOutputStream());
                    completes.incrementAndGet();
                    reply(exchange, 200, "<CompleteMultipartUploadResult><Location>http://storage.test/vod-raw/retry-upload</Location>"
                            + "<Bucket>vod-raw</Bucket><Key>retry-upload</Key><ETag>\"complete\"</ETag>"
                            + "</CompleteMultipartUploadResult>", "application/xml");
                } else if (mode == Mode.TRANSIENT_PART && method.equals("DELETE")) {
                    aborts.incrementAndGet();
                    exchange.sendResponseHeaders(204, -1);
                } else {
                    unexpected.incrementAndGet();
                    reply(exchange, 400, "unexpected request", "text/plain");
                }
            }
        }

        private static String parameter(String query, String name) {
            if (query == null) return null;
            for (String pair : query.split("&")) {
                if (pair.startsWith(name + "=")) return pair.substring(name.length() + 1);
            }
            return null;
        }

        private static void steadyBody(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, 24L * 4096);
            byte[] chunk = new byte[4096];
            for (int i = 0; i < 24; i++) {
                exchange.getResponseBody().write(chunk);
                exchange.getResponseBody().flush();
                if (i < 23) {
                    try { Thread.sleep(100); } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Fake S3 response interrupted");
                    }
                }
            }
        }

        private static void unavailable(HttpExchange exchange) throws IOException {
            reply(exchange, 503, "<Error><Code>SlowDown</Code><Message>temporarily unavailable</Message>"
                    + "<Resource>/vod-raw/retry-upload</Resource><RequestId>fake</RequestId></Error>", "application/xml");
        }

        private static void reply(HttpExchange exchange, int status, String body, String contentType) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
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
