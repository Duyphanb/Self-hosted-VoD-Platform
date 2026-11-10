package com.vodplatform.worker.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkerStorageTransportTests {
    @Test
    void rawDownloadCanContinueBeyondTheReadStallBudget() {
        assertTimeoutPreemptively(Duration.ofSeconds(12), () -> {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/vod-raw/raw-test", exchange -> {
                try (exchange) {
                    var chunk = new byte[4096];
                    exchange.sendResponseHeaders(200, 20L * chunk.length);
                    for (int i = 0; i < 20; i++) {
                        exchange.getResponseBody().write(chunk);
                        exchange.getResponseBody().flush();
                        if (i < 19) {
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                }
            });
            server.start();
            var properties = properties(server, 1_000);
            try (var client = new StorageConfiguration().workerMinioClient(properties)) {
                var storage = new WorkerObjectStorage(client, properties);
                long start = System.nanoTime();
                assertEquals(20L * 4096, storage.downloadRaw("raw-test", OutputStream.nullOutputStream()));
                assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() > properties.readTimeoutMs());
            } finally {
                server.stop(0);
            }
        });
    }

    @Test
    void persistentRetryableStatusStopsAfterFiveGetsWithASafeError() {
        assertTimeoutPreemptively(Duration.ofSeconds(12), () -> {
            var gets = new AtomicInteger();
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/vod-raw/raw-test", exchange -> {
                try (exchange) {
                    gets.incrementAndGet();
                    var error = "<Error><Code>SlowDown</Code><Message>private diagnostic</Message></Error>"
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/xml");
                    exchange.sendResponseHeaders(503, error.length);
                    exchange.getResponseBody().write(error);
                }
            });
            server.start();
            var properties = properties(server, 5_000);
            try (var client = new StorageConfiguration().workerMinioClient(properties)) {
                var storage = new WorkerObjectStorage(client, properties);
                var failure = assertThrows(IOException.class,
                        () -> storage.downloadRaw("raw-test", OutputStream.nullOutputStream()));
                assertEquals("Object storage GET failed", failure.getMessage());
                assertNull(failure.getCause());
                assertEquals(5, gets.get());
            } finally {
                server.stop(0);
            }
        });
    }

    private static StorageProperties properties(HttpServer server, int readTimeout) {
        return StorageProperties.fromEnvironment(WorkerStorageConfigurationTests.environment()
                .withProperty("MINIO_ENDPOINT", "http://127.0.0.1:" + server.getAddress().getPort())
                .withProperty("MINIO_READ_TIMEOUT_MS", Integer.toString(readTimeout)));
    }
}
