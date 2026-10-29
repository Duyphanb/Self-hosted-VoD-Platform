package com.vodplatform.common.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.MinioException;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class StorageConfigurationTests {
    @Test
    void rejectsUnknownLengthWithoutReadingOrCallingSdk() throws Exception {
        MinioClient client = mock(MinioClient.class);
        InputStream input = mock(InputStream.class);
        assertThrows(IllegalArgumentException.class,
                () -> new MinioObjectStorage(client).put("vod-raw", "key", input, -1, "video/mp4"));
        verifyNoInteractions(input, client);
    }

    @Test
    void passesStreamAndKnownLengthWithoutClosingCallerInput() throws Exception {
        MinioClient client = mock(MinioClient.class);
        InputStream input = mock(InputStream.class);
        new MinioObjectStorage(client).put("vod-raw", "key", input, 123, "video/mp4");
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
        var storage = new MinioObjectStorage(client);
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
    void rejectsBlankCredentialsAndBuildsConfiguredClientWithoutNetwork() throws Exception {
        var config = new StorageConfiguration();
        assertThrows(IllegalArgumentException.class, () -> config.minioClient("http://localhost:9000", "", "test", 10000, 60000, 60000));
        try (MinioClient client = config.minioClient("http://localhost:9000", "test-backend", "test-only-secret", 10000, 60000, 60000)) {
            assertNotNull(config.objectStorage(client));
        }
    }
}
