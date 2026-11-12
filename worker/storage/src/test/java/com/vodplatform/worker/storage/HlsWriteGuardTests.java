package com.vodplatform.worker.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.junit.jupiter.api.Test;

class HlsWriteGuardTests {
    @Test
    void cancelsAnInFlightWriteBlocksNewWritesAndAllowsCleanup() throws Exception {
        var received = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var stopped = new AtomicBoolean();
        var dispatcher = new Dispatcher();
        var guard = new HlsWriteGuard(dispatcher);
        var client = new OkHttpClient.Builder().dispatcher(dispatcher).addInterceptor(guard).build();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(threads);
            server.createContext("/", exchange -> {
                try (exchange) {
                    if (exchange.getRequestMethod().equals("PUT")) {
                        received.countDown();
                        release.await(5, TimeUnit.SECONDS);
                    }
                    exchange.sendResponseHeaders(204, -1);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            server.start();
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            var put = new Request.Builder().url(endpoint).put(RequestBody.create(new byte[0], null)).build();
            guard.begin(stopped::get);
            try {
                var result = threads.submit(() -> {
                    try (var response = client.newCall(put).execute()) {
                        return response.code();
                    }
                });
                assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
                stopped.set(true);
                guard.cancelActiveWrites();
                assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(IOException.class);
                assertThatThrownBy(() -> client.newCall(put).execute())
                        .isInstanceOf(IOException.class).hasMessage("Worker HLS upload cancelled");
                var delete = new Request.Builder().url(endpoint).delete().build();
                try (var response = client.newCall(delete).execute()) {
                    assertThat(response.code()).isEqualTo(204);
                }
            } finally {
                guard.end();
                release.countDown();
                server.stop(0);
            }
        }
    }
}
