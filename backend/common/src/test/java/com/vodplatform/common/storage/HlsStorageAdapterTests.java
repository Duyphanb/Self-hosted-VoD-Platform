package com.vodplatform.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.vodplatform.shared.storage.ObjectMissingException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Real SDK over HTTP; the 404/403 codes reproduce the pinned Silo GetObject-only probe. */
class HlsStorageAdapterTests {
    @ParameterizedTest
    @CsvSource({"404,NoSuchKey,true", "403,AccessDenied,false", "404,NoSuchBucket,false", "400,InvalidRequest,false"})
    void onlyConfirmedMissingKeyMapsToMissing(int status, String code, boolean missing) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = ("<Error><Code>" + code + "</Code><Message>private diagnostic</Message></Error>")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/xml");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        var properties = StorageConfigurationTests.properties("http://127.0.0.1:" + server.getAddress().getPort(), 1000, 1000, 1000);
        try (var client = new StorageConfiguration().minioClient(properties)) {
            var storage = new StorageConfiguration().objectStorage(client, properties);
            assertThatThrownBy(() -> storage.get("vod-hls", "private-key"))
                    .isInstanceOf(missing ? ObjectMissingException.class : IOException.class)
                    .satisfies(error -> assertThat(error instanceof ObjectMissingException).isEqualTo(missing))
                    .hasNoCause().hasMessage(missing ? "Object storage object not found" : "Object storage GET failed");
        } finally { server.stop(0); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exposesLengthWhenKnownWithoutAnExtraHeadRequest(boolean known) throws Exception {
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        byte[] body = "segment bytes".getBytes(StandardCharsets.US_ASCII);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, known ? body.length : 0);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        var properties = StorageConfigurationTests.properties("http://127.0.0.1:" + server.getAddress().getPort(), 1000, 1000, 1000);
        try (var client = new StorageConfiguration().minioClient(properties)) {
            var storage = new StorageConfiguration().objectStorage(client, properties);
            try (var object = storage.get("vod-hls", "key")) {
                assertThat(object.size()).isEqualTo(known ? java.util.OptionalLong.of(body.length) : java.util.OptionalLong.empty());
                assertThat(object.readAllBytes()).isEqualTo(body);
            }
            assertThat(requests).hasValue(1);
        } finally { server.stop(0); }
    }
}
