package com.vodplatform.worker.queue;

import static com.vodplatform.worker.probe.WorkerFailureCode.INSUFFICIENT_DISK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vodplatform.worker.probe.WorkerFailureException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class WorkerDiskBudgetTests {
    private static final long MIB = 1024L * 1024;
    private static final Path JOB = Path.of("job");

    @Test
    void checksRawSizeAndBothBranchesOfTheExactPostProbeBudget() {
        var free = new AtomicLong(3 * MIB);
        try (var disk = new WorkerDiskBudget(5000, 1, ignored -> free.get())) {
            disk.beforeDownload(JOB, 2 * MIB);
            assertThatThrownBy(() -> disk.beforeDownload(JOB, 2 * MIB + 1))
                    .isInstanceOfSatisfying(WorkerFailureException.class,
                            failure -> assertThat(failure.code()).isEqualTo(INSUFFICIENT_DISK));
            var duration = Duration.ofNanos(1_000_000_001L);
            long b = 16 * MIB + 625_001;
            assertThat(disk.requiredAfterProbe(3 * b, duration)).isEqualTo(MIB + b);
            assertThat(disk.requiredAfterProbe(1, duration)).isEqualTo(MIB + 2 * b - 1);
            free.set(MIB + 2 * b - 2);
            assertThatThrownBy(() -> disk.afterProbe(JOB, 1, duration))
                    .isInstanceOfSatisfying(WorkerFailureException.class,
                            failure -> assertThat(failure.code()).isEqualTo(INSUFFICIENT_DISK));
        }
    }

    @Test
    void tripsWhileAnOperationIsRunningWhenSpaceDropsBelowTheReserve() throws InterruptedException {
        var free = new AtomicLong(2 * MIB);
        var cancelled = new CountDownLatch(1);
        try (var disk = new WorkerDiskBudget(5000, 1, ignored -> free.get());
                var watch = disk.watch(JOB, cancelled::countDown)) {
            assertThat(watch.failed()).isFalse();
            free.set(MIB - 1);
            assertThat(cancelled.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(watch::check).isInstanceOfSatisfying(WorkerFailureException.class,
                    failure -> assertThat(failure.code()).isEqualTo(INSUFFICIENT_DISK));
        }
    }
}
