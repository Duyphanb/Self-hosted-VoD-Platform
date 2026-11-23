package com.vodplatform.auth.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.vodplatform.auth.persistence.SessionState;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SessionStateCache {

    private static final Duration MAX_AGE = Duration.ofSeconds(30);
    private static final int MAX_ENTRIES = 10_000;

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
        while (true) {
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
            try {
                LoadedState result = shared.finished.join();
                if (isFresh(result)) {
                    return result.state();
                }
            } catch (CompletionException failure) {
                if (failure.getCause() instanceof RuntimeException cause) {
                    throw cause;
                }
                throw failure;
            }
        }
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
        try {
            // A previous load may have published between the fast-path miss and registration.
            LoadedState cached = entries.getIfPresent(id);
            LoadedState result = isFresh(cached) ? cached : loadFromStore(id);
            publish(id, result, null);
        } catch (RuntimeException | Error failure) {
            publish(id, null, failure);
        }
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

    private LoadedState loadFromStore(UUID id) {
        long startedAt = ticker.read();
        Optional<SessionState> state = reader.read(id, false);
        return new LoadedState(startedAt, state);
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
