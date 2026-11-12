package com.vodplatform.worker.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.minio.MinioClient;
import io.minio.errors.MinioException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

class WorkerStorageConfigurationTests {
    @Test
    void forceDeleteRejectsBroadOrMalformedPrefixesBeforeCallingStorage() {
        var client = mock(MinioClient.class);
        var storage = new WorkerObjectStorage(client, StorageProperties.fromEnvironment(environment()));
        String asset = "video-assets/12345678-1234-1234-1234-123456789012/";
        for (String prefix : new String[]{"", "video-assets/", asset, asset + "attempt-1",
                asset + "attempt-0/", asset + "attempt-1/master.m3u8", asset + "attempt-1/../"}) {
            assertThatThrownBy(() -> storage.deleteHlsAttemptPrefix(prefix))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid HLS attempt prefix");
        }
        verifyNoInteractions(client);
    }

    @Test
    void storageSettingsHaveNoCredentialOrTimeoutDefaults() {
        assertThatThrownBy(() -> StorageProperties.fromEnvironment(new MockEnvironment()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MINIO_CONNECT_TIMEOUT_MS must be a positive integer");
        assertThatThrownBy(() -> StorageProperties.fromEnvironment(
                environment().withProperty("MINIO_SECRET_KEY", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MINIO_ACCESS_KEY and MINIO_SECRET_KEY must not be blank");
        assertThatThrownBy(() -> StorageProperties.fromEnvironment(
                environment().withProperty("MINIO_READ_TIMEOUT_MS", "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MINIO_READ_TIMEOUT_MS must be a positive integer");
        assertThat(StorageProperties.fromEnvironment(environment()).toString())
                .isEqualTo("StorageProperties[redacted]");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://worker:%s@localhost:9000",
            "http://localhost:9000?secret=%s",
            "http://localhost:bad-port?secret=%s"
    })
    @ExtendWith(OutputCaptureExtension.class)
    void rejectsUnsafeEndpointsWithoutLeakingCredentials(String template, CapturedOutput output) {
        String marker = "synthetic-storage-credential";
        assertThatThrownBy(() -> new StorageConfiguration().workerStorageProperties(environment()
                .withProperty("MINIO_ENDPOINT", template.formatted(marker))
                .withProperty("MINIO_ACCESS_KEY", marker)
                .withProperty("MINIO_SECRET_KEY", marker)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MINIO_ENDPOINT must be a valid HTTP(S) origin")
                .hasNoCause();
        assertThat(output.getAll()).doesNotContain(marker);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MINIO_ENDPOINT", "MINIO_ACCESS_KEY", "MINIO_SECRET_KEY"})
    void omitsUnresolvableSettingsFromStartupDiagnostics(String name) {
        assertThatThrownBy(() -> StorageProperties.fromEnvironment(
                environment().withProperty(name, "synthetic-marker-${unresolved-test-property}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(name + " could not be resolved")
                .hasNoCause();
    }

    @Test
    void effectiveClientBoundsStallsAndKeepsRawGetFreeOfATotalDeadline() throws Exception {
        var config = new StorageConfiguration();
        var properties = config.workerStorageProperties(environment());
        try (var client = config.workerMinioClient(properties)) {
            var asyncClient = ReflectionTestUtils.getField(client, "asyncClient");
            var transport = (OkHttpClient) ReflectionTestUtils.getField(asyncClient, "httpClient");
            assertThat(transport.connectTimeoutMillis()).isEqualTo(10_000);
            assertThat(transport.readTimeoutMillis()).isEqualTo(60_000);
            assertThat(transport.writeTimeoutMillis()).isEqualTo(60_000);
            assertThat(transport.callTimeoutMillis()).isZero();
            assertThat(transport.dns()).isInstanceOf(BoundedStorageDns.class);
            assertThat(transport.dispatcher().getMaxRequests()).isEqualTo(64);
            assertThat(transport.dispatcher().getMaxRequestsPerHost()).isEqualTo(32);
            assertThat(StorageConfiguration.MAX_CONCURRENT_PUTS).isLessThan(32);
        }
    }

    @Test
    void statGetAndPutHideSdkDiagnostics() throws Exception {
        var client = mock(MinioClient.class);
        var diagnostic = new MinioException("signed request details", new IOException("transport details"));
        when(client.statObject(any())).thenThrow(diagnostic);
        when(client.getObject(any())).thenThrow(diagnostic);
        when(client.putObject(any())).thenThrow(diagnostic);
        var storage = new WorkerObjectStorage(client, StorageProperties.fromEnvironment(environment()));
        assertThatThrownBy(() -> storage.rawSize("test-key"))
                .isInstanceOf(IOException.class).hasMessage("Object storage STAT failed").hasNoCause();
        assertThatThrownBy(() -> storage.downloadRaw("test-key", OutputStream.nullOutputStream()))
                .isInstanceOf(IOException.class).hasMessage("Object storage GET failed").hasNoCause();
        assertThatThrownBy(() -> storage.putHls("test-key", InputStream.nullInputStream(), 0, "video/mp2t"))
                .isInstanceOf(IOException.class).hasMessage("Object storage PUT failed").hasNoCause();
    }

    static MockEnvironment environment() {
        return new MockEnvironment().withProperty("MINIO_ENDPOINT", "http://127.0.0.1:1")
                .withProperty("MINIO_ACCESS_KEY", "worker-only")
                .withProperty("MINIO_SECRET_KEY", "worker-only-secret")
                .withProperty("MINIO_BUCKET_RAW", "vod-raw")
                .withProperty("MINIO_BUCKET_HLS", "vod-hls")
                .withProperty("MINIO_CONNECT_TIMEOUT_MS", "10000")
                .withProperty("MINIO_READ_TIMEOUT_MS", "60000")
                .withProperty("MINIO_WRITE_TIMEOUT_MS", "60000");
    }
}
