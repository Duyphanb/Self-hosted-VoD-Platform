package com.vodplatform.worker.testsupport;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

/**
 * A stand-in media tool for process tests (#123; SECURITY.md lists "deadline and process-tree kill, including a
 * fake binary that spawns a grandchild"). {@link #command} runs it on the test JVM's own {@code java}, so
 * runner tests work on any OS. {@link #stallingScript} writes a POSIX shell executable for tests that need a
 * single binary path, such as {@code FFPROBE_PATH}; it needs {@code sh}, {@code sleep} and {@code mv}.
 */
public final class FakeMediaTool {
    /** The spawning modes write "toolPid childPid" here, atomically, once the child has started. */
    public static final String PID_FILE = "fake-media-tool.pids";

    private FakeMediaTool() {
    }

    /**
     * Modes: {@code exit <code> <stdout> <stderr>}; {@code read-stdin} (prints {@code stdin-bytes=N} at end of
     * input); {@code print-env} (prints NAME=value lines); {@code flood <bytes>} (writes that many bytes to
     * stdout and to stderr, interleaved, so a reader of one stream alone deadlocks); {@code spawn-and-sleep
     * <directory>} (starts a {@code sleep} child that shares its output pipes, records both PIDs in
     * {@link #PID_FILE}, then sleeps); {@code sleep}.
     */
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "exit" -> {
                System.out.print(args[2]);
                System.err.print(args[3]);
                System.out.flush();
                System.err.flush();
                System.exit(Integer.parseInt(args[1]));
            }
            case "read-stdin" -> System.out.print("stdin-bytes=" + System.in.transferTo(OutputStream.nullOutputStream()));
            case "print-env" -> new TreeMap<>(System.getenv()).forEach((name, value) -> System.out.println(name + "=" + value));
            case "flood" -> flood(Long.parseLong(args[1]));
            case "spawn-and-sleep" -> {
                Process child = new ProcessBuilder(command("sleep")).inheritIO().start();
                recordPids(Path.of(args[1]), ProcessHandle.current().pid(), child.pid());
                sleepForever();
            }
            case "sleep" -> sleepForever();
            default -> throw new IllegalArgumentException("Unknown mode " + args[0]);
        }
    }

    /** The command that runs {@code mode} with {@code arguments} on the test JVM's {@code java}. */
    public static List<String> command(String mode, String... arguments) {
        List<String> command = new ArrayList<>(List.of(javaBinary(), "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC",
                "-Xmx64m", "-cp", classpath(), FakeMediaTool.class.getName(), mode));
        command.addAll(Arrays.asList(arguments));
        return command;
    }

    /**
     * Writes an executable shell script into {@code directory} that ignores its arguments, starts a
     * {@code sleep 600} child sharing its output pipes, records both PIDs in {@link #PID_FILE} and waits for the
     * child. Only for POSIX systems; the FFmpeg-tagged tests run it in the worker image.
     */
    public static Path stallingScript(Path directory) {
        Path script = directory.resolve("fake-media-tool.sh");
        Path pids = directory.resolve(PID_FILE);
        try {
            Files.writeString(script, "#!/bin/sh\nsleep 600 &\necho \"$$ $!\" > '" + pids + ".tmp' && mv '" + pids
                    + ".tmp' '" + pids + "'\nwait\n", StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return script;
    }

    /** Waits up to {@code timeout} for {@link #PID_FILE} in {@code directory}: the tool's PID, then its child's. */
    public static List<Long> awaitPids(Path directory, Duration timeout) throws InterruptedException {
        Path pids = directory.resolve(PID_FILE);
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!Files.exists(pids)) {
            if (System.nanoTime() - deadline > 0) throw new IllegalStateException("The fake tool recorded no PIDs");
            Thread.sleep(20);
        }
        try {
            return Arrays.stream(Files.readString(pids, StandardCharsets.UTF_8).strip().split(" ")).map(Long::valueOf).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void recordPids(Path directory, long tool, long child) throws IOException {
        Path temporary = directory.resolve(PID_FILE + ".tmp");
        Files.writeString(temporary, tool + " " + child, StandardCharsets.UTF_8);
        Files.move(temporary, directory.resolve(PID_FILE), StandardCopyOption.ATOMIC_MOVE);
    }

    private static void flood(long bytes) {
        byte[] chunk = new byte[8192];
        Arrays.fill(chunk, (byte) 'x');
        PrintStream out = System.out;
        PrintStream err = System.err;
        for (long written = 0; written < bytes; written += chunk.length) {
            int length = (int) Math.min(chunk.length, bytes - written);
            out.write(chunk, 0, length);
            err.write(chunk, 0, length);
        }
        out.flush();
        err.flush();
    }

    private static void sleepForever() throws InterruptedException {
        while (true) Thread.sleep(60_000);
    }

    private static String javaBinary() {
        return ProcessHandle.current().info().command()
                .orElseGet(() -> Path.of(System.getProperty("java.home"), "bin", "java").toString());
    }

    private static String classpath() {
        try {
            return Path.of(FakeMediaTool.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
