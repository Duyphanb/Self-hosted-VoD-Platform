package com.vodplatform.common.storage;

import com.vodplatform.shared.storage.ObjectStorage;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class StorageConfiguration {
    @Bean(destroyMethod = "close")
    MinioClient minioClient(
            @Value("${MINIO_ENDPOINT:http://minio:9000}") String endpoint,
            @Value("${MINIO_ACCESS_KEY:vod_backend}") String accessKey,
            @Value("${MINIO_SECRET_KEY:change-me-backend-only}") String secretKey,
            @Value("${MINIO_CONNECT_TIMEOUT_MS:10000}") long connectTimeout,
            @Value("${MINIO_READ_TIMEOUT_MS:60000}") long readTimeout,
            @Value("${MINIO_WRITE_TIMEOUT_MS:60000}") long writeTimeout) {
        if (accessKey.isBlank() || secretKey.isBlank()) {
            throw new IllegalArgumentException("Storage credentials must not be blank");
        }
        if (connectTimeout <= 0 || readTimeout <= 0 || writeTimeout <= 0) {
            throw new IllegalArgumentException("Storage timeouts must be positive");
        }
        MinioClient client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey)
                .region("us-east-1").build();
        client.setTimeout(connectTimeout, readTimeout, writeTimeout);
        return client;
    }

    @Bean
    ObjectStorage objectStorage(MinioClient client) {
        return new MinioObjectStorage(client);
    }
}
