package com.vodplatform.worker.storage;

import io.minio.Http;
import io.minio.MinioClient;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class StorageConfiguration {
    static final int MAX_REQUESTS_PER_HOST = 32;
    static final int MAX_CONCURRENT_PUTS = 8;
    static final long HEALTH_TIMEOUT_MS = 2_000;

    @Bean
    StorageProperties workerStorageProperties(Environment env) {
        return StorageProperties.fromEnvironment(env);
    }

    @Bean(destroyMethod = "close")
    MinioClient workerMinioClient(StorageProperties properties) {
        return client(properties, Long.MAX_VALUE);
    }

    @Bean
    WorkerObjectStorage workerObjectStorage(MinioClient client, StorageProperties properties) {
        return new WorkerObjectStorage(client, properties);
    }

    @Bean(name = "workerStorage", destroyMethod = "close")
    StorageHealthIndicator workerStorageHealth(StorageProperties properties) {
        return new StorageHealthIndicator(client(properties, HEALTH_TIMEOUT_MS), properties.rawBucket());
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
                // Steady raw downloads can outlast each stall budget.
                .callTimeout(readiness ? HEALTH_TIMEOUT_MS : 0, TimeUnit.MILLISECONDS)
                .build();
        MinioClient client = MinioClient.builder().endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey()).region("us-east-1")
                .httpClient(httpClient, true).build();
        if (readiness) client.setRetry(Set.of(), 0L, 1);
        else client.setRetry(Http.RETRIABLE_STATUS_CODES, 100L, 5);
        return client;
    }
}
