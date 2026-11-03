package com.vodplatform.worker.probe.process;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one media tool (FFprobe here, FFmpeg in Issue 5.5) under the ADR-010 D6-D10 process rules
 * (SECURITY.md Media Processing Safeguards): a typed argument list started without a shell, stdin closed,
 * an environment cleared to PATH, LANG and HOME, stdout and stderr drained concurrently into bounded
 * {@link OutputSink}s so a full pipe never stalls the tool, and a deadline that kills the whole process
 * tree, descendants first. Exit values are reported, never interpreted: callers treat any non-zero exit as
 * a failure and must not depend on specific values, which differ between FFmpeg releases.
 */
public final class MediaProcessRunner {
    /** The only variables a media tool inherits; everything else in the worker environment is dropped. */
    public static final List<String> INHERITED_VARIABLES = List.of("PATH", "LANG", "HOME");
    static final Duration KILL_WAIT = Duration.ofSeconds(5);
    static final Duration DRAIN_GRACE = Duration.ofSeconds(2);
    private static final Logger log = LoggerFactory.getLogger(MediaProcessRunner.class);
    private final Map<String, String> environment;

    /** Inherits PATH, LANG and HOME from the worker's own environment. */
    public MediaProcessRunner() {
        this(System::getenv);
    }

    /** Inherits PATH, LANG and HOME, where set, from {@code parentEnvironment} (a lookup by variable name). */
    public MediaProcessRunner(Function<String, String> parentEnvironment) {
        Objects.requireNonNull(parentEnvironment, "parentEnvironment");
        Map<String, String> inherited = new LinkedHashMap<>();
        for (String name : INHERITED_VARIABLES) {
            String value = parentEnvironment.apply(name);
            if (value != null) inherited.put(name, value);
        }
        this.environment = Map.copyOf(inherited);
    }

    /**
     * Starts {@code command} (executable first) and waits until it exits and its output is drained, or until
     * {@code timeout} has passed; then the process tree is killed and the result is {@link Result#timedOut()}.
     *
     * @throws IOException when the executable cannot be started
     * @throws InterruptedException when the calling thread is interrupted; the process tree is killed first
     */
    public Result run(List<String> command, Duration timeout, OutputSink stdout, OutputSink stderr)
            throws IOException, InterruptedException {
        List<String> arguments = checked(command);
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        ProcessBuilder builder = new ProcessBuilder(arguments);
        builder.environment().clear();
        builder.environment().putAll(environment);
        long deadline = System.nanoTime() + timeout.toNanos();
        Process process = builder.start();
        try {
            closeStdin(process);
            Drain out = Drain.start(process.getInputStream(), stdout, "media-stdout-" + process.pid());
            Drain err = Drain.start(process.getErrorStream(), stderr, "media-stderr-" + process.pid());
            if (!process.waitFor(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) {
                killTree(process);
                out.await(DRAIN_GRACE);
                err.await(DRAIN_GRACE);
                return Result.TIMED_OUT;
            }
            // The deadline covers draining too: a descendant that outlived the tool may hold the pipes open.
            long drainDeadline = Math.max(deadline, System.nanoTime() + DRAIN_GRACE.toNanos());
            if (!out.await(until(drainDeadline)) || !err.await(until(drainDeadline))) {
                log.warn("Media process {} exited but its output stayed open past the deadline", process.pid());
                return Result.TIMED_OUT;
            }
            out.rethrowSinkFailure();
            err.rethrowSinkFailure();
            return new Result(false, process.exitValue());
        } finally {
            if (process.isAlive()) killTree(process);
        }
    }

    /**
     * Kills {@code process} and its descendants. {@link Process#descendants()} lists breadth first, so the
     * reversed list starts with the deepest processes: each one dies while its parent is still alive to reap
     * it, instead of being orphaned (and escaping the snapshot) when an ancestor dies first.
     */
    static void killTree(Process process) {
        boolean interrupted = Thread.interrupted();
        boolean waiting = true;
        long deadline = System.nanoTime() + KILL_WAIT.toNanos();
        List<ProcessHandle> killed = new ArrayList<>();
        try {
            for (int round = 0; round < 3; round++) {
                List<ProcessHandle> descendants = process.descendants().toList().reversed();
                if (descendants.isEmpty()) break;
                for (ProcessHandle descendant : descendants) {
                    descendant.destroyForcibly();
                    killed.add(descendant);
                    if (waiting && !awaitExit(descendant, deadline)) {
                        waiting = false;
                        interrupted = true;
                    }
                }
            }
            process.destroyForcibly();
            killed.add(process.toHandle());
            if (waiting && !awaitExit(process.toHandle(), deadline)) interrupted = true;
            long survivors = killed.stream().filter(ProcessHandle::isAlive).count();
            if (survivors > 0) {
                log.warn("{} media process(es) of tree {} still alive after a forced kill", survivors, process.pid());
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Waits for {@code handle} to disappear until {@code deadline}; false when interrupted while waiting. */
    private static boolean awaitExit(ProcessHandle handle, long deadline) {
        while (handle.isAlive() && System.nanoTime() - deadline < 0) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return true;
    }

    private static Duration until(long deadline) {
        return Duration.ofNanos(Math.max(deadline - System.nanoTime(), 0));
    }

    private static void closeStdin(Process process) {
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // The tool already exited; it can no longer read stdin either way.
        }
    }

    private static List<String> checked(List<String> command) {
        if (command == null || command.isEmpty()) throw new IllegalArgumentException("Command must name an executable");
        for (String argument : command) {
            if (argument == null || argument.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Command arguments must be text without NUL characters");
            }
        }
        if (command.getFirst().isBlank()) throw new IllegalArgumentException("Command must name an executable");
        return List.copyOf(command);
    }

    /** How the tool finished: its exit code, or {@link #timedOut()} when the deadline killed its tree. */
    public record Result(boolean timedOut, int exitCode) {
        static final Result TIMED_OUT = new Result(true, -1);

        public boolean succeeded() {
            return !timedOut && exitCode == 0;
        }
    }

    /** Copies one process stream into its sink on a daemon thread until end of stream. */
    private static final class Drain implements Runnable {
        private final InputStream in;
        private final OutputSink sink;
        private final Thread thread;
        private volatile RuntimeException failure;

        private Drain(InputStream in, OutputSink sink, String name) {
            this.in = in;
            this.sink = sink;
            this.thread = Thread.ofPlatform().daemon().name(name).unstarted(this);
        }

        static Drain start(InputStream in, OutputSink sink, String name) {
            Drain drain = new Drain(in, sink, name);
            drain.thread.start();
            return drain;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            try (in) {
                for (int read; (read = in.read(buffer)) != -1; ) {
                    // After a sink failure keep reading, so the tool never blocks on a full pipe.
                    if (read > 0 && failure == null) accept(buffer, read);
                }
            } catch (IOException ignored) {
                // The stream closed because the process died; there is nothing more to drain.
            } finally {
                if (failure == null) {
                    try {
                        sink.end();
                    } catch (RuntimeException e) {
                        failure = e;
                    }
                }
            }
        }

        private void accept(byte[] buffer, int length) {
            try {
                sink.accept(buffer, length);
            } catch (RuntimeException e) {
                failure = e;
            }
        }

        boolean await(Duration wait) throws InterruptedException {
            return thread.join(wait);
        }

        void rethrowSinkFailure() {
            if (failure != null) throw new IllegalStateException("Media output sink failed", failure);
        }
    }
}
