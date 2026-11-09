package com.vodplatform.worker.probe;

import static com.vodplatform.worker.probe.WorkerFailureCode.INTERNAL_ERROR;
import static com.vodplatform.worker.probe.WorkerFailureCode.MEDIA_LIMIT_EXCEEDED;
import static com.vodplatform.worker.probe.WorkerFailureCode.PROBE_FAILED;
import static com.vodplatform.worker.probe.WorkerFailureCode.PROBE_TIMEOUT;
import static com.vodplatform.worker.probe.WorkerFailureCode.UNSUPPORTED_MEDIA;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vodplatform.worker.probe.process.CappedOutput;
import com.vodplatform.worker.probe.process.MediaProcessRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Service ordering and the outcome mapping; FfprobeServiceFfmpegTests runs it against the D25 binary. */
class FfprobeServiceTests {
    private static final ProbeSettings POLICY = new ProbeSettings("ffprobe", 30, 10_800, 1920, 1080, 8);
    private static final String VALID_JSON = "{\"streams\":[{\"codec_type\":\"video\",\"codec_name\":\"h264\",\"width\":640,"
            + "\"height\":360,\"disposition\":{\"attached_pic\":0}}],\"format\":{\"duration\":\"13.000000\"}}";
    @TempDir
    Path directory;

    @Test
    void anUnknownKeyExtensionFailsBeforeAnyToolRuns() throws IOException {
        Path input = Files.writeString(directory.resolve("raw.mp4"), "not media");
        // FFprobe cannot start here: reaching it would report INTERNAL_ERROR instead of UNSUPPORTED_MEDIA.
        FfprobeService service = new FfprobeService(new ProbeSettings(directory.resolve("no-ffprobe").toString(), 30, 10_800,
                1920, 1080, 8), new MediaProcessRunner());

        for (String extension : Arrays.asList("avi", "m3u8", "MP4", "", null)) {
            assertCode(() -> service.probe(input, extension), UNSUPPORTED_MEDIA);
        }
        assertCode(() -> service.probe(input, "mp4"), INTERNAL_ERROR);
    }

    @Test
    void theInputMustBeAnAbsoluteRegularFile() throws IOException {
        FfprobeService service = new FfprobeService(POLICY, new MediaProcessRunner());
        Path folder = Files.createDirectory(directory.resolve("job-1"));

        for (Path input : Arrays.asList(null, Path.of("raw.mp4"), directory.resolve("missing.mp4"), folder)) {
            assertThatThrownBy(() -> service.probe(input, "mp4")).as(String.valueOf(input)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void theDeadlineWinsOverEveryOtherOutcome() {
        ProbeDiagnostics diagnostics = diagnostics("[matroska,webm @ 0x1] Number of tracks exceeds max_streams (8)");

        assertCode(() -> FfprobeService.interpret(new MediaProcessRunner.Result(true, -1), json(VALID_JSON), diagnostics, POLICY),
                PROBE_TIMEOUT);
    }

    @Test
    void ffprobeReportsOfTheStreamCapAndUnlistedDecodersWinOverTheExitCode() {
        String cap = "[mov,mp4,m4a,3gp,3g2,mj2 @ 0x1] Number of streams exceeds max_streams parameter (8), see the documentation";
        String decoder = "[mpeg2video @ 0x2] Codec (mpeg2video) not on whitelist 'h264,hevc'";

        // Matroska drops the extra track and exits 0 with valid JSON: only the log shows the violation.
        assertCode(() -> interpret(0, VALID_JSON, cap), MEDIA_LIMIT_EXCEEDED);
        assertCode(() -> interpret(1, "", cap), MEDIA_LIMIT_EXCEEDED);
        assertCode(() -> interpret(1, "", decoder), UNSUPPORTED_MEDIA);
        assertCode(() -> interpret(0, VALID_JSON, decoder), UNSUPPORTED_MEDIA);
        assertCode(() -> interpret(1, "", decoder, cap), MEDIA_LIMIT_EXCEEDED);
    }

    @Test
    void anyOtherNonZeroExitOrOversizedOutputFailsTheProbe() {
        assertCode(() -> interpret(1, VALID_JSON, "[mov @ 0x1] moov atom not found"), PROBE_FAILED);
        assertCode(() -> interpret(-9, VALID_JSON), PROBE_FAILED);
        assertCode(() -> interpret(255, ""), PROBE_FAILED);
        CappedOutput oversized = new CappedOutput(FfprobeService.MAX_JSON_BYTES);
        byte[] chunk = " ".repeat(FfprobeService.MAX_JSON_BYTES + 1).getBytes(UTF_8);
        oversized.accept(chunk, chunk.length);
        assertCode(() -> FfprobeService.interpret(new MediaProcessRunner.Result(false, 0), oversized, diagnostics(), POLICY),
                PROBE_FAILED);
    }

    @Test
    void aCleanRunIsParsedUnderTheD26Rules() {
        MediaMetadata metadata = interpret(0, VALID_JSON, "[h264 @ 0x1] unrelated warning promoted to error");

        assertThat(metadata).isEqualTo(new MediaMetadata(Duration.ofSeconds(13), 640, 360, 0, "h264", null));
    }

    @Test
    void protocolAndFormatWhitelistMessagesAreNotDecoderRejections() {
        ProbeDiagnostics diagnostics = diagnostics("[file @ 0x1] Protocol 'http' not on whitelist 'file'!",
                "[mov @ 0x2] Format not on whitelist 'mov'");

        assertThat(diagnostics.decoderNotAllowed()).isFalse();
        assertThat(diagnostics.streamCapExceeded()).isFalse();
        assertCode(() -> FfprobeService.interpret(new MediaProcessRunner.Result(false, 1), json(""), diagnostics, POLICY),
                PROBE_FAILED);
    }

    private static MediaMetadata interpret(int exitCode, String json, String... logLines) {
        return FfprobeService.interpret(new MediaProcessRunner.Result(false, exitCode), json(json), diagnostics(logLines), POLICY);
    }

    private static ProbeDiagnostics diagnostics(String... lines) {
        ProbeDiagnostics diagnostics = new ProbeDiagnostics();
        for (String line : lines) diagnostics.accept(line);
        return diagnostics;
    }

    private static CappedOutput json(String json) {
        CappedOutput output = new CappedOutput(FfprobeService.MAX_JSON_BYTES);
        byte[] bytes = json.getBytes(UTF_8);
        output.accept(bytes, bytes.length);
        return output;
    }

    private static void assertCode(ThrowingCallable call, WorkerFailureCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(WorkerFailureException.class,
                failure -> assertThat(failure.code()).isEqualTo(code));
    }
}
