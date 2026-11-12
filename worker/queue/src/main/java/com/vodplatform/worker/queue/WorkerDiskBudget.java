package com.vodplatform.worker.queue;

import static com.vodplatform.worker.probe.WorkerFailureCode.INSUFFICIENT_DISK;
import static com.vodplatform.worker.probe.WorkerFailureCode.INTERNAL_ERROR;

import com.vodplatform.worker.probe.WorkerFailureException;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** D27 disk calculations and the one-second encode/upload reserve monitor. */
final class WorkerDiskBudget implements AutoCloseable {
    private static final long MIB = 1024L * 1024;
    private final long bytesPerSecond;
    private final long reserve;
    private final FreeSpace freeSpace;
    private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("worker-disk-watchdog").factory());

    WorkerDiskBudget(int outputKbps, int reserveMib) {
        this(outputKbps, reserveMib, path -> Files.getFileStore(path).getUsableSpace());
    }

    WorkerDiskBudget(int outputKbps, int reserveMib, FreeSpace freeSpace) {
        if (outputKbps <= 0 || reserveMib <= 0) throw new IllegalArgumentException("Disk budget must be positive");
        this.bytesPerSecond = outputKbps * 125L;
        this.reserve = reserveMib * MIB;
        this.freeSpace = freeSpace;
    }

    void beforeDownload(Path jobDirectory, long rawBytes) {
        if (usableSpace(jobDirectory) - reserve < rawBytes) throw insufficient();
    }

    long requiredAfterProbe(long rawBytes, Duration duration) {
        BigDecimal seconds = BigDecimal.valueOf(duration.getSeconds()).add(BigDecimal.valueOf(duration.getNano(), 9));
        long output = seconds.multiply(BigDecimal.valueOf(bytesPerSecond)).setScale(0, RoundingMode.CEILING)
                .longValueExact() + 16 * MIB;
        return reserve + Math.max(output, 2 * output - rawBytes);
    }

    void afterProbe(Path jobDirectory, long rawBytes, Duration duration) {
        if (usableSpace(jobDirectory) < requiredAfterProbe(rawBytes, duration)) throw insufficient();
    }

    Watch watch(Path jobDirectory, Runnable cancelUpload) {
        return new Watch(jobDirectory, cancelUpload);
    }

    private long usableSpace(Path path) {
        try {
            return freeSpace.bytes(path);
        } catch (IOException ignored) {
            throw new WorkerFailureException(INTERNAL_ERROR, "Worker free space could not be determined");
        }
    }

    private static WorkerFailureException insufficient() {
        return new WorkerFailureException(INSUFFICIENT_DISK, "Worker free space is below the disk budget");
    }

    @Override
    public void close() {
        sampler.shutdownNow();
    }

    @FunctionalInterface
    interface FreeSpace {
        long bytes(Path path) throws IOException;
    }

    final class Watch implements AutoCloseable {
        private final ScheduledFuture<?> future;
        private volatile WorkerFailureException failure;
        private boolean closed;

        private Watch(Path directory, Runnable cancelUpload) {
            sample(directory, cancelUpload);
            future = sampler.scheduleAtFixedRate(() -> sample(directory, cancelUpload), 1, 1, TimeUnit.SECONDS);
        }

        private synchronized void sample(Path directory, Runnable cancelUpload) {
            if (closed || failure != null) return;
            try {
                if (usableSpace(directory) < reserve) throw insufficient();
            } catch (WorkerFailureException diskFailure) {
                failure = diskFailure;
                cancelUpload.run();
            }
        }

        boolean failed() {
            return failure != null;
        }

        void check() {
            WorkerFailureException result = failure;
            if (result != null) throw result;
        }

        @Override
        public synchronized void close() {
            closed = true;
            future.cancel(false);
        }
    }
}
