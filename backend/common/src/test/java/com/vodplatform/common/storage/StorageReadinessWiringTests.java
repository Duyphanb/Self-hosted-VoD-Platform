package com.vodplatform.common.storage;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.test.util.ReflectionTestUtils;

class StorageReadinessWiringTests {
    @Test
    void healthBeanUsesCappedIndependentTransportAndDoesNotRetryStatusFailures() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger();
        server.createContext("/", exchange -> {
            try (exchange) {
                calls.incrementAndGet();
                byte[] body = "<Error><Code>SlowDown</Code><Message>unavailable</Message></Error>"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/xml");
                exchange.sendResponseHeaders(503, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        var properties = StorageConfigurationTests.properties(
                "http://127.0.0.1:" + server.getAddress().getPort(), 10000, 60000, 60000);
        var config = new StorageConfiguration();
        try (var main = config.minioClient(properties); var indicator = config.storageHealthIndicator(properties)) {
            var healthClient = (io.minio.MinioClient) ReflectionTestUtils.getField(indicator, "client");
            assertNotNull(healthClient);
            var transport = StorageConfigurationTests.transport(healthClient);
            assertEquals(2000, transport.connectTimeoutMillis());
            assertEquals(2000, transport.readTimeoutMillis());
            assertEquals(2000, transport.writeTimeoutMillis());
            assertEquals(2000, transport.callTimeoutMillis());
            assertNotSame(StorageConfigurationTests.transport(main).dispatcher(), transport.dispatcher());
            assertEquals(Status.DOWN, indicator.health().getStatus());
            assertEquals(1, calls.get(), "The shipped health bean must not retry a 503 response");
        } finally {
            server.stop(0);
        }
    }
}
