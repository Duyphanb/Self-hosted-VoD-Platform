package com.vodplatform.worker.probe.process;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vodplatform.worker.testsupport.FakeMediaTool;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The runner against {@link FakeMediaTool}, a stand-in tool on the test JVM, so these run on any OS. */
class MediaProcessRunnerTests {
    private static final Duration GENEROUS = Duration.ofSeconds(60);
    private final MediaProcessRunner runner = new MediaProcessRunner();
    @TempDir
    Path directory;

    @Test
    void reportsTheExitCodeAndDrainsBothStreams() throws Exception {
        CappedOutput out = new CappedOutput(1024);
        List<String> errorLines = new ArrayList<>();

        MediaProcessRunner.Result result = runner.run(FakeMediaTool.command("exit", "3", "[streams]", "an error"),
                GENEROUS, out, new LineOutput(64, errorLines::add));

        assertThat(result).isEqualTo(new MediaProcessRunner.Result(false, 3));
        assertThat(result.succeeded()).isFalse();
        assertThat(new String(out.bytes(), UTF_8)).isEqualTo("[streams]");
        assertThat(errorLines).containsExactly("an error");
    }

    @Test
    void closesStdinSoAToolThatReadsItSeesEndOfInput() throws Exception {
        CappedOutput out = new CappedOutput(64);

        MediaProcessRunner.Result result = runner.run(FakeMediaTool.command("read-stdin"), GENEROUS, out, new CappedOutput(4096));

        assertThat(result.succeeded()).isTrue();
        assertThat(new String(out.bytes(), UTF_8)).isEqualTo("stdin-bytes=0");
    }

    @Test
    void passesOnlyPathLangAndHomeToTheTool() throws Exception {
        Map<String, String> parent = new HashMap<>();
        parent.put("PATH", System.getenv("PATH"));
        parent.put("LANG", "C.UTF-8");
        parent.put("HOME", directory.toString());
        parent.put("MINIO_WORKER_SECRET_KEY", "must-not-leak");
        parent.put("JAVA_TOOL_OPTIONS", "-Dmust.not.leak=true");
        CappedOutput out = new CappedOutput(256 * 1024);

        MediaProcessRunner.Result result = new MediaProcessRunner(parent::get)
                .run(FakeMediaTool.command("print-env"), GENEROUS, out, new CappedOutput(4096));

        assertThat(result.succeeded()).isTrue();
        Map<String, String> environment = new HashMap<>();
        for (String line : new String(out.bytes(), UTF_8).split("\\R")) {
            if (!line.isEmpty()) environment.put(line.substring(0, line.indexOf('=')), line.substring(line.indexOf('=') + 1));
        }
        assertThat(environment).containsEntry("LANG", "C.UTF-8").containsEntry("HOME", directory.toString()).containsKey("PATH");
        // The JDK adds SystemRoot to every child environment on Windows, where CreateProcess needs it.
        assertThat(environment.keySet()).isSubsetOf(Set.of("PATH", "LANG", "HOME", "SystemRoot"));
        assertThat(MediaProcessRunner.INHERITED_VARIABLES).containsExactly("PATH", "LANG", "HOME");
    }

    @Test
    void drainsStdoutAndStderrConcurrentlyKeepingOnlyTheirCaps() throws Exception {
        // The tool interleaves 4 MiB on each stream: reading one stream to its end first would deadlock on the
        // other's full pipe until the deadline.
        long bytes = 4L * 1024 * 1024;
        CappedOutput out = new CappedOutput(1024);
        CappedOutput err = new CappedOutput(512);

        MediaProcessRunner.Result result = runner.run(FakeMediaTool.command("flood", String.valueOf(bytes)), GENEROUS, out, err);

        assertThat(result.succeeded()).isTrue();
        assertThat(out.totalBytes()).isEqualTo(bytes);
        assertThat(err.totalBytes()).isEqualTo(bytes);
        assertThat(out.bytes()).hasSize(1024);
        assertThat(err.bytes()).hasSize(512);
        assertThat(out.overflowed()).isTrue();
    }

    @Test
    void deadlineKillsTheToolAndTheChildItSpawned() throws Exception {
        long started = System.nanoTime();
        CompletableFuture<MediaProcessRunner.Result> run = CompletableFuture.supplyAsync(() -> run(
                FakeMediaTool.command("spawn-and-sleep", directory.toString()), Duration.ofSeconds(4)));
        List<ProcessHandle> tree = handles(FakeMediaTool.awaitPids(directory, GENEROUS));
        assertThat(tree).allMatch(ProcessHandle::isAlive);

        MediaProcessRunner.Result result = run.get(60, TimeUnit.SECONDS);

        assertThat(result.timedOut()).isTrue();
        assertThat(result.succeeded()).isFalse();
        assertThat(tree).noneMatch(ProcessHandle::isAlive);
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(Duration.ofSeconds(4).plus(MediaProcessRunner.KILL_WAIT).plus(MediaProcessRunner.DRAIN_GRACE));
    }

    @Test
    void interruptKillsTheTreeBeforePropagating() throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                runner.run(FakeMediaTool.command("spawn-and-sleep", directory.toString()), GENEROUS,
                        new CappedOutput(64), new CappedOutput(64));
            } catch (Throwable e) {
                thrown.set(e);
            }
        });
        List<ProcessHandle> tree = handles(FakeMediaTool.awaitPids(directory, GENEROUS));

        caller.interrupt();

        assertThat(caller.join(GENEROUS)).isTrue();
        assertThat(thrown.get()).isInstanceOf(InterruptedException.class);
        assertThat(tree).noneMatch(ProcessHandle::isAlive);
    }

    @Test
    void anExecutableThatCannotStartIsAnIoFailure() {
        String missing = directory.resolve("missing-tool").toString();

        assertThatThrownBy(() -> runner.run(List.of(missing), GENEROUS, new CappedOutput(1), new CappedOutput(1)))
                .isInstanceOf(IOException.class);
    }

    @Test
    void aFailingSinkIsReportedAfterTheToolWasStillDrained() {
        OutputSink failing = (buffer, length) -> {
            throw new IllegalStateException("sink defect");
        };

        assertThatThrownBy(() -> runner.run(FakeMediaTool.command("flood", "1048576"), GENEROUS, failing, new CappedOutput(16)))
                .isInstanceOf(IllegalStateException.class).hasRootCauseMessage("sink defect");
    }

    @Test
    void rejectsAnythingButAPlainArgumentListAndAPositiveTimeout() {
        CappedOutput sink = new CappedOutput(1);
        assertThatThrownBy(() -> runner.run(List.of(), GENEROUS, sink, sink)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runner.run(Arrays.asList("ffprobe", null), GENEROUS, sink, sink))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runner.run(List.of("ffprobe", "file:a\0b"), GENEROUS, sink, sink))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runner.run(List.of(" "), GENEROUS, sink, sink)).isInstanceOf(IllegalArgumentException.class);
        for (Duration timeout : Arrays.asList(null, Duration.ZERO, Duration.ofSeconds(-1))) {
            assertThatThrownBy(() -> runner.run(List.of("ffprobe"), timeout, sink, sink)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private MediaProcessRunner.Result run(List<String> command, Duration timeout) {
        try {
            return runner.run(command, timeout, new CappedOutput(64), new CappedOutput(64));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Handles taken while the processes live, so a later reuse of their PIDs cannot look alive. */
    private static List<ProcessHandle> handles(List<Long> pids) {
        return pids.stream().map(pid -> ProcessHandle.of(pid).orElseThrow()).toList();
    }
}
