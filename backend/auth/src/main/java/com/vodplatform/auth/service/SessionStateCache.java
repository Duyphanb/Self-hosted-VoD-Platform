package com.vodplatform.auth.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.vodplatform.auth.persistence.SessionState;
import com.vodplatform.auth.security.AuthStateUnavailableException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class SessionStateCache {

    private static final Duration MAX_AGE = Duration.ofSeconds(30);
    private static final int MAX_ENTRIES = 10_000;
    // Each attempt reads or waits for at most the reader's timeout (10 s by default, 20 s at most), so a
    // request gets 503 well before the edge's 60-second read timeout.
    private static final int MAX_LOAD_ATTEMPTS = 2;

    private final SessionStateReader reader;
    private final Ticker ticker;
    private final Cache<UUID, LoadedState> entries;
    private final ConcurrentHashMap<UUID, Load> inFlight = new ConcurrentHashMap<>();

    @Autowired
    public SessionStateCache(SessionStateReader reader, Ticker ticker) {
        this(reader, ticker, MAX_ENTRIES);
    }

    SessionStateCache(SessionStateReader reader, Ticker ticker, int maximumSize) {
        this.reader = reader;
        this.ticker = ticker;
        this.entries = Caffeine.newBuilder().maximumSize(maximumSize).expireAfterWrite(MAX_AGE).ticker(ticker).build();
    }

    public Optional<SessionState> read(UUID id) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // The reader then sees the caller's uncommitted rows, which must never reach the shared cache.
            return reader.read(id, false);
        }
        for (int attempt = 0; attempt < MAX_LOAD_ATTEMPTS; attempt++) {
            LoadedState cached = entries.getIfPresent(id);
            if (isFresh(cached)) {
                return cached.state();
            }

            Load candidate = new Load();
            Load existing = inFlight.putIfAbsent(id, candidate);
            if (existing == null) {
                populate(id);
            }
            Load shared = existing == null ? candidate : existing;
            LoadedState result = await(shared);
            if (isFresh(result)) {
                return result.state();
            }
        }
        throw new AuthStateUnavailableException(null);
    }

    // Every caller waits at most the reader's timeout. A load outlives callers that gave up: it stays registered
    // until its physical read ends, so later misses join it instead of starting a second read.
    private LoadedState await(Load load) {
        try {
            return load.finished.get(reader.timeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException slow) {
            throw new AuthStateUnavailableException(slow);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AuthStateUnavailableException(interrupted);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (failed.getCause() instanceof Error error) {
                throw error;
            }
            throw new AuthStateUnavailableException(failed.getCause());
        }
    }

    // Visible for tests: the number of cached sessions after Caffeine's pending evictions.
    long cachedSessionCount() {
        entries.cleanUp();
        return entries.estimatedSize();
    }

    public void invalidate(UUID id) {
        inFlight.compute(id, (key, load) -> {
            entries.invalidate(key);
            if (load != null) {
                load.invalidated = true;
            }
            return load;
        });
    }

    private void populate(UUID id) {
        // A previous load may have published between the fast-path miss and registration.
        LoadedState cached = entries.getIfPresent(id);
        if (isFresh(cached)) {
            publish(id, cached, null);
            return;
        }
        // The age counts from the start of this physical read (ADR-012 D6).
        long startedAt = ticker.read();
        reader.readAsync(id, false).whenComplete((state, failure) -> {
            if (failure == null) {
                publish(id, new LoadedState(startedAt, state), null);
            } else {
                publish(id, null, unwrapped(failure));
            }
        });
    }

    private static Throwable unwrapped(Throwable failure) {
        if (failure instanceof CompletionException && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    private void publish(UUID id, LoadedState result, Throwable failure) {
        inFlight.computeIfPresent(id, (key, running) -> {
            if (running.invalidated) {
                running.finished.complete(null);
            } else if (failure != null) {
                running.finished.completeExceptionally(failure);
            } else {
                if (isFresh(result)) {
                    entries.put(key, result);
                }
                running.finished.complete(result);
            }
            return null;
        });
    }

    private boolean isFresh(LoadedState value) {
        return value != null && ticker.read() - value.startedAt() < MAX_AGE.toNanos();
    }

    private record LoadedState(long startedAt, Optional<SessionState> state) {
    }

    // The map holds only concurrent loads. Invalidation and publication share its per-key compute.
    private static final class Load {
        private final CompletableFuture<LoadedState> finished = new CompletableFuture<>();
        private boolean invalidated;
    }
}
