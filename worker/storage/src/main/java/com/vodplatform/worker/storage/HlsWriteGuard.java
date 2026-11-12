package com.vodplatform.worker.storage;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import okhttp3.Call;
import okhttp3.Dispatcher;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/** Cancels the single worker upload's writes while allowing multipart abort and later prefix cleanup. */
final class HlsWriteGuard implements Interceptor {
    private final Dispatcher dispatcher;
    private final AtomicReference<BooleanSupplier> active = new AtomicReference<>();

    HlsWriteGuard(Dispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    void begin(BooleanSupplier cancelled) throws IOException {
        check(cancelled);
        if (!active.compareAndSet(null, cancelled)) throw new IOException("Worker HLS upload already active");
    }

    void end() {
        active.set(null);
    }

    void cancelActiveWrites() {
        BooleanSupplier upload = active.get();
        if (upload == null || !upload.getAsBoolean()) return;
        Stream.concat(dispatcher.queuedCalls().stream(), dispatcher.runningCalls().stream())
                .filter(call -> isWrite(call.request())).forEach(Call::cancel);
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        BooleanSupplier upload = active.get();
        if (upload != null && isWrite(chain.request())) check(upload);
        return chain.proceed(chain.request());
    }

    static void check(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean()) throw new IOException("Worker HLS upload cancelled");
    }

    private static boolean isWrite(Request request) {
        return request.method().equals("PUT") || request.method().equals("POST");
    }
}
