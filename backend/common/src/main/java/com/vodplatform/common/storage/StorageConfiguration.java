package com.vodplatform.common.storage;

import com.vodplatform.shared.storage.ObjectStorage;
import io.minio.Http;
import io.minio.MinioClient;
import java.util.concurrent.TimeUnit;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfiguration {
    static final int MAX_REQUESTS_PER_HOST = 32;
    static final int MAX_CONCURRENT_PUTS = 8;
    static final long HEALTH_TIMEOUT_MS = 2_000;

    @Bean(destroyMethod = "close")
    MinioClient minioClient(StorageProperties properties) {
        return client(properties, Long.MAX_VALUE);
    }

    static MinioClient client(StorageProperties properties, long timeoutCap) {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(64);
        dispatcher.setMaxRequestsPerHost(MAX_REQUESTS_PER_HOST);
        long connect = Math.min(properties.connectTimeoutMs(), timeoutCap);
        long read = Math.min(properties.readTimeoutMs(), timeoutCap);
        long write = Math.min(properties.writeTimeoutMs(), timeoutCap);
        boolean readiness = timeoutCap != Long.MAX_VALUE;
        OkHttpClient httpClient = new OkHttpClient.Builder().dispatcher(dispatcher)
                .dns(new BoundedStorageDns(connect))
                .connectTimeout(connect, TimeUnit.MILLISECONDS)
                .readTimeout(read, TimeUnit.MILLISECONDS)
                .writeTimeout(write, TimeUnit.MILLISECONDS)
                // Application GET bodies may keep flowing beyond the socket stall budgets.
                // Only readiness has a total call cap; the PUT gate preserves abort headroom.
                .callTimeout(readiness ? Math.max(connect, Math.max(read, write)) : 0, TimeUnit.MILLISECONDS)
                .build();
        MinioClient client = MinioClient.builder().endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey()).region("us-east-1")
                .httpClient(httpClient, true).build();
        // Status retries remain inside the same dispatched call, preserving the PUT-gate invariant.
        if (readiness) client.setRetry(java.util.Set.of(), 0L, 1);
        else client.setRetry(Http.RETRIABLE_STATUS_CODES, 100L, 5);
        return client;
    }

    @Bean
    ObjectStorage objectStorage(MinioClient client, StorageProperties properties) {
        return new MinioObjectStorage(client, properties.connectTimeoutMs());
    }

    @Bean(destroyMethod = "close")
    StorageHealthIndicator storageHealthIndicator(StorageProperties properties) {
        // A separate dispatcher and short call budget keep readiness independent of uploads.
        return new StorageHealthIndicator(client(properties, HEALTH_TIMEOUT_MS), properties.hlsBucket());
    }
}
