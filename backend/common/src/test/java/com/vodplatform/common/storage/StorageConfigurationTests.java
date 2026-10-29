package com.vodplatform.common.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.MinioException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

class StorageConfigurationTests {
    @Test
    void rejectsUnknownLengthWithoutReadingOrCallingSdk() throws Exception {
        MinioClient client = mock(MinioClient.class);
        InputStream input = mock(InputStream.class);
        assertThrows(IllegalArgumentException.class,
                () -> new MinioObjectStorage(client, 100).put("vod-raw", "key", input, -1, "video/mp4"));
        verifyNoInteractions(input, client);
    }

    @Test
    void passesStreamAndKnownLengthWithoutClosingCallerInput() throws Exception {
        MinioClient client = mock(MinioClient.class);
        InputStream input = mock(InputStream.class);
        new MinioObjectStorage(client, 100).put("vod-raw", "key", input, 123, "video/mp4");
        var args = org.mockito.ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(client).putObject(args.capture());
        assertEquals(123L, args.getValue().objectSize());
        assertEquals(5L * 1024 * 1024, args.getValue().partSize());
        verifyNoInteractions(input);
    }

    @Test
    void hidesSdkDiagnosticsForEveryOperation() throws Exception {
        MinioClient client = mock(MinioClient.class);
        MinioException diagnostic = new MinioException("signed request details", new IOException("transport details"));
        when(client.putObject(any())).thenThrow(diagnostic);
        when(client.getObject(any())).thenThrow(diagnostic);
        doThrow(diagnostic).when(client).removeObject(any());
        var storage = new MinioObjectStorage(client, 100);
        IOException put = assertThrows(IOException.class,
                () -> storage.put("vod-raw", "key", mock(InputStream.class), 1, "video/mp4"));
        IOException get = assertThrows(IOException.class, () -> storage.get("vod-hls", "key"));
        IOException delete = assertThrows(IOException.class, () -> storage.delete("vod-raw", "key"));
        assertEquals("Object storage PUT failed", put.getMessage());
        assertEquals("Object storage GET failed", get.getMessage());
        assertEquals("Object storage DELETE failed", delete.getMessage());
        for (IOException failure : new IOException[] {put, get, delete}) {
            assertNull(failure.getCause());
            assertEquals(0, failure.getSuppressed().length);
        }
    }

    @Test
    void namedTimeoutsAndDispatcherLimitsReachTheEffectiveSdkTransport() throws Exception {
        var config = new StorageConfiguration();
        var properties = properties("http://127.0.0.1:9000", 33333, 111111, 222222);
        try (MinioClient client = config.minioClient(properties)) {
            var http = transport(client);
            assertEquals(33333, http.connectTimeoutMillis());
            assertEquals(111111, http.readTimeoutMillis());
            assertEquals(222222, http.writeTimeoutMillis());
            assertEquals(222222, http.callTimeoutMillis());
            assertInstanceOf(BoundedStorageDns.class, http.dns());
            assertEquals(32, http.dispatcher().getMaxRequestsPerHost());
            assertTrue(StorageConfiguration.MAX_CONCURRENT_PUTS < http.dispatcher().getMaxRequestsPerHost());
            assertNotNull(config.objectStorage(client, properties));
        }
    }

    @Test
    void mapsSdkWrappedTransportFailuresWithoutExposingDiagnostics() throws Exception {
        MinioClient client = mock(MinioClient.class);
        var diagnostic = new IllegalStateException(new IOException("internal URL, bucket and object key"));
        when(client.putObject(any())).thenThrow(diagnostic);
        when(client.getObject(any())).thenThrow(diagnostic);
        doThrow(diagnostic).when(client).removeObject(any());
        var storage = new MinioObjectStorage(client, 100);
        assertSafe(assertThrows(IOException.class,
                () -> storage.put("vod-raw", "key", InputStream.nullInputStream(), 0, "video/mp4")), "PUT");
        assertSafe(assertThrows(IOException.class, () -> storage.get("vod-hls", "key")), "GET");
        assertSafe(assertThrows(IOException.class, () -> storage.delete("vod-raw", "key")), "DELETE");
    }

    @Test
    void rejectsNullCallerArgumentsBeforeIoAndPreservesOtherSdkProgrammingErrors() throws Exception {
        MinioClient client = mock(MinioClient.class);
        var storage = new MinioObjectStorage(client, 100);
        assertThrows(NullPointerException.class, () -> storage.put("vod-raw", "key", null, 0, "video/mp4"));
        assertThrows(NullPointerException.class,
                () -> storage.put("vod-raw", "key", InputStream.nullInputStream(), 0, null));
        verifyNoInteractions(client);
        var programmingError = new IllegalStateException("not a transport error");
        when(client.getObject(any())).thenThrow(programmingError);
        assertSame(programmingError, assertThrows(IllegalStateException.class, () -> storage.get("vod-hls", "key")));
    }

    @Test
    void realSdkClosedPortReturnsSafeIoExceptionForEveryOperation() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var properties = properties("http://127.0.0.1:" + port, 200, 200, 200);
        var config = new StorageConfiguration();
        try (MinioClient client = config.minioClient(properties)) {
            var storage = config.objectStorage(client, properties);
            assertSafe(assertThrows(IOException.class,
                    () -> storage.put("vod-raw", "key", InputStream.nullInputStream(), 0, "video/mp4")), "PUT");
            assertSafe(assertThrows(IOException.class, () -> storage.get("vod-hls", "key")), "GET");
            assertSafe(assertThrows(IOException.class, () -> storage.delete("vod-raw", "key")), "DELETE");
        }
    }

    @Test
    void overloadedPutAdmissionFailsSafelyWithinTheBudgetWithoutReadingInput() throws Exception {
        MinioClient client = mock(MinioClient.class);
        var admitted = new CountDownLatch(StorageConfiguration.MAX_CONCURRENT_PUTS);
        var release = new CountDownLatch(1);
        when(client.putObject(any())).thenAnswer(invocation -> {
            admitted.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        });
        var storage = new MinioObjectStorage(client, 100);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, StorageConfiguration.MAX_CONCURRENT_PUTS)
                    .mapToObj(i -> executor.submit(() -> {
                        storage.put("vod-raw", "key", InputStream.nullInputStream(), 0, "video/mp4");
                        return null;
                    })).toList();
            try {
                assertTrue(admitted.await(3, TimeUnit.SECONDS));
                InputStream input = mock(InputStream.class);
                assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () ->
                        assertSafe(assertThrows(IOException.class,
                                () -> storage.put("vod-raw", "key", input, 0, "video/mp4")), "PUT"));
                verifyNoInteractions(input);
            } finally { release.countDown(); }
            for (var future : futures) future.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void bindsValidatedPropertiesAndRequiresExplicitCredentials() {
        var runner = new ApplicationContextRunner().withUserConfiguration(StorageConfiguration.class)
                .withPropertyValues("storage.endpoint=http://localhost:9000", "storage.connect-timeout-ms=10",
                        "storage.read-timeout-ms=11", "storage.write-timeout-ms=12", "storage.hls-bucket=vod-hls");
        runner.run(context -> assertNotNull(context.getStartupFailure()));
        runner.withPropertyValues("storage.access-key=test-only", "storage.secret-key=test-only-secret")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(11, context.getBean(StorageProperties.class).readTimeoutMs());
                    assertEquals("StorageProperties[redacted]", context.getBean(StorageProperties.class).toString());
                });
        assertThrows(IllegalArgumentException.class, () -> properties("http://localhost:9000", 0, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new StorageProperties("http://localhost:9000", " ", "secret", 1, 1, 1, "vod-hls"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://localhost", "http://user:secret@localhost", "http://localhost/path",
            "http://localhost?secret=value", "http://localhost:0", "http://localhost:70000", "invalid"})
    void rejectsInvalidEndpointWithoutEchoingItsValue(String endpoint) {
        var failure = assertThrows(IllegalArgumentException.class, () -> properties(endpoint, 1, 1, 1));
        assertEquals("Storage endpoint must be a valid HTTP(S) origin", failure.getMessage());
    }

    static StorageProperties properties(String endpoint, long connect, long read, long write) {
        return new StorageProperties(endpoint, "test-backend", "test-only-secret", connect, read, write, "vod-hls");
    }

    static OkHttpClient transport(MinioClient client) {
        return (OkHttpClient) ReflectionTestUtils.getField(ReflectionTestUtils.getField(client, "asyncClient"), "httpClient");
    }

    static void assertSafe(IOException failure, String operation) {
        assertEquals("Object storage " + operation + " failed", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }
}
