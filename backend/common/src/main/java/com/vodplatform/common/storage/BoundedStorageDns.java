package com.vodplatform.common.storage;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import okhttp3.Dns;

/** Bounds storage DNS waits even when the JVM resolver ignores thread interruption. */
final class BoundedStorageDns implements Dns {
    private static final ThreadPoolExecutor SHARED_LOOKUPS = lookupExecutor(16);
    private final long timeoutMs;
    private final Dns delegate;
    private final ThreadPoolExecutor executor;

    BoundedStorageDns(long timeoutMs) {
        this(timeoutMs, Dns.SYSTEM, SHARED_LOOKUPS);
    }

    BoundedStorageDns(long timeoutMs, Dns delegate, ThreadPoolExecutor executor) {
        this.timeoutMs = timeoutMs;
        this.delegate = delegate;
        this.executor = executor;
    }

    static ThreadPoolExecutor lookupExecutor(int maximumLookups) {
        // JVM-wide bounded handoff: no queue, no replacement for a still-blocked lookup.
        // Daemon threads expire after 30 idle seconds; clients do not own/close the shared pool.
        return new ThreadPoolExecutor(0, maximumLookups, 30, TimeUnit.SECONDS, new SynchronousQueue<>(),
                Thread.ofPlatform().daemon(true).name("storage-dns-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        Future<List<InetAddress>> pending;
        try {
            pending = executor.submit(() -> delegate.lookup(hostname));
        } catch (RejectedExecutionException ignored) {
            throw unavailable();
        }
        try {
            return pending.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (TimeoutException ignored) {
            // Cancellation is best effort; a native resolver may retain this pool slot until it exits.
            pending.cancel(true);
            throw unavailable();
        } catch (ExecutionException ignored) {
            throw unavailable();
        }
    }

    private static UnknownHostException unavailable() {
        return new UnknownHostException("Object storage DNS unavailable");
    }
}
