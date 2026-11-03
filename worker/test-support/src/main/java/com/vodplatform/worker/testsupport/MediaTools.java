package com.vodplatform.worker.testsupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the worker image's ffmpeg/ffprobe for FFmpeg-tagged tests. The binaries come from the same
 * {@code FFMPEG_PATH}/{@code FFPROBE_PATH} variables the worker image sets; a missing variable fails the
 * test instead of skipping it, so a CI run cannot pass without exercising the binary.
 */
public final class MediaTools {
    /** JUnit tag of tests that need the binaries; excluded from the default build (worker/pom.xml). */
    public static final String FFMPEG_TAG = "ffmpeg";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);
    private static final int MAX_CAPTURED_CHARS = 64 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final String ffmpeg;
    private final String ffprobe;

    public MediaTools(String ffmpeg, String ffprobe) {
        this.ffmpeg = requireText(ffmpeg, "ffmpeg");
        this.ffprobe = requireText(ffprobe, "ffprobe");
    }

    public static MediaTools fromEnvironment() {
        return new MediaTools(System.getenv("FFMPEG_PATH"), System.getenv("FFPROBE_PATH"));
    }

    public Result ffmpeg(List<String> arguments) {
        return run(ffmpeg, arguments, DEFAULT_TIMEOUT);
    }

    public Result ffprobe(List<String> arguments) {
        return run(ffprobe, arguments, DEFAULT_TIMEOUT);
    }

    /** Probes with the trusted demuxer and file-only protocol, as ADR-010 requires of the worker. */
    public Result probe(Path file, MediaFixture.Demuxer demuxer, List<String> extraInputOptions) {
        List<String> arguments = new ArrayList<>(List.of("-hide_banner", "-loglevel", "error",
                "-protocol_whitelist", "file"));
        arguments.addAll(extraInputOptions);
        arguments.addAll(List.of("-f", demuxer.ffmpegName(), "-show_format", "-show_streams",
                "-of", "json", "file:" + file.toAbsolutePath()));
        return ffprobe(arguments);
    }

    public JsonNode probeJson(Path file, MediaFixture.Demuxer demuxer) {
        Result result = probe(file, demuxer, List.of());
        if (result.exitCode() != 0) throw new IllegalStateException("ffprobe failed for " + file + ": " + result.stderr());
        return JSON.readTree(result.stdout());
    }

    private static Result run(String binary, List<String> arguments, Duration timeout) {
        List<String> command = new ArrayList<>(arguments.size() + 1);
        command.add(binary);
        command.addAll(arguments);
        Path stdout = null;
        Path stderr = null;
        try {
            stdout = Files.createTempFile("vod-tool-", ".out");
            stderr = Files.createTempFile("vod-tool-", ".err");
            Process process = new ProcessBuilder(command)
                    .redirectInput(ProcessBuilder.Redirect.from(Path.of(nullDevice()).toFile()))
                    .redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile())
                    .start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly().waitFor();
                throw new IllegalStateException(command.getFirst() + " exceeded " + timeout);
            }
            return new Result(process.exitValue(), read(stdout), read(stderr));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot run " + binary, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running " + binary, e);
        } finally {
            deleteQuietly(stdout);
            deleteQuietly(stderr);
        }
    }

    private static String read(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        return text.length() > MAX_CAPTURED_CHARS ? text.substring(0, MAX_CAPTURED_CHARS) : text;
    }

    private static String nullDevice() {
        return System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null";
    }

    private static void deleteQuietly(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A leftover temp file does not affect the result.
        }
    }

    private static String requireText(String value, String tool) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("FFmpeg-tagged tests need " + tool.toUpperCase() + "_PATH; run them through "
                    + "deploy/tests/worker-ffmpeg-tests.sh, which uses the worker image binary");
        }
        return value;
    }

    public record Result(int exitCode, String stdout, String stderr) {
    }
}
