package com.vodplatform.worker.transcode;

import static com.vodplatform.worker.probe.WorkerFailureCode.ENCODE_FAILED;
import static com.vodplatform.worker.probe.WorkerFailureCode.ENCODE_TIMEOUT;
import static com.vodplatform.worker.testsupport.MediaFixture.COVER_ART_ONLY_MP4;
import static com.vodplatform.worker.testsupport.MediaFixture.H264_AAC_1080P30_MP4;
import static com.vodplatform.worker.testsupport.MediaFixture.H264_AAC_MP4;
import static com.vodplatform.worker.testsupport.MediaFixture.HEVC_MAIN10_HDR_AC3_MKV;
import static com.vodplatform.worker.testsupport.MediaFixture.TRUNCATED_FASTSTART_MP4;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.vodplatform.worker.probe.FfprobeService;
import com.vodplatform.worker.probe.MediaMetadata;
import com.vodplatform.worker.probe.ProbeSettings;
import com.vodplatform.worker.probe.WorkerFailureCode;
import com.vodplatform.worker.probe.WorkerFailureException;
import com.vodplatform.worker.probe.process.MediaProcessRunner;
import com.vodplatform.worker.testsupport.HlsOutputCheck;
import com.vodplatform.worker.testsupport.HlsPlaylist;
import com.vodplatform.worker.testsupport.MediaFixture;
import com.vodplatform.worker.testsupport.MediaFixtures;
import com.vodplatform.worker.testsupport.MediaTools;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Issue 5.5 on the #123 generated fixtures with the ADR-010 D25 binary (the worker image's FFMPEG_PATH and
 * FFPROBE_PATH): the production command, the process runner and the D27 validator together. The policy values
 * are those of ADR-010 D27/D28; the deadline values are only test values, because #124 measures them.
 *
 * <p>Each valid family is probed by the Issue 5.4 service and encoded by the Issue 5.5 service, which already
 * requires a zero exit and a valid output; the test-support {@link HlsOutputCheck} then restates the D27 checks
 * independently and every segment is probed for the profile. This is also the D27 evidence that neither
 * {@code -xerror} nor the 1-second EXTINF tolerance rejects a valid file.
 */
@Tag(MediaTools.FFMPEG_TAG)
class FfmpegHlsServiceFfmpegTests {
    @Test
    void rejectsAnInputThroughALinkOutsideTheJob(@TempDir Path temp) throws IOException {
        Path job = Files.createDirectory(temp.resolve("job"));
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Files.writeString(outside.resolve("raw.mp4"), "not media");
        Path linked = Files.createSymbolicLink(job.resolve("linked"), outside);
        MediaMetadata metadata = new MediaMetadata(Duration.ofSeconds(13), 640, 360, 0, false, "h264", 1000);

        assertThatThrownBy(() -> service(policy()).transcode(job, linked.resolve("raw.mp4"), "mp4", metadata))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(job.resolve("hls")).doesNotExist();
    }

    private static final int SEGMENT_SECONDS = 6;
    private static final double SEGMENT_TOLERANCE_SECONDS = 0.1;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern PSNR_AVERAGE = Pattern.compile("PSNR .*average:([0-9.]+|inf)");
    private static final AtomicInteger JOBS = new AtomicInteger();
    private static final Map<MediaFixture, Encoded> ENCODED = new EnumMap<>(MediaFixture.class);
    @TempDir
    static Path directory;
    private static MediaTools tools;
    private static MediaFixtures fixtures;

    @BeforeAll
    static void fixtures() {
        tools = MediaTools.fromEnvironment();
        fixtures = new MediaFixtures(tools, directory.resolve("fixtures"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // fixture, expected output width and height, expected frame rate, whether audio is present
            "H264_AAC_MP4, 640, 360, 30/1, true",
            "H264_AAC_1080P30_MP4, 1280, 720, 30/1, true",
            "VP9_OPUS_WEBM, 640, 360, 30/1, true",
            "H264_HIGH10_FLAC_MKV, 640, 360, 30/1, true",
            "HEVC_MAIN10_HDR_AC3_MKV, 640, 360, 30/1, true",
            "PORTRAIT_H264_AAC_MP4, 360, 640, 30/1, true",
            "PORTRAIT_720X1280_MP4, 720, 1280, 30/1, true",
            "PORTRAIT_1080X1920_MP4, 720, 1280, 30/1, true",
            "ROTATED_90_MP4, 360, 640, 30/1, true",
            "ROTATED_MINUS_90_MKV, 360, 640, 30/1, true",
            "ROTATED_180_MP4, 640, 360, 30/1, true",
            "ROTATE_TAG_90_MKV, 360, 640, 30/1, true",
            "SILENT_H264_MP4, 640, 360, 30/1, false",
            // Odd sizes keep the aspect and are rounded down to even; nothing is upscaled into the box.
            "ODD_641X359_WEBM, 640, 358, 30/1, true",
            "SD_853X480_MP4, 852, 480, 30/1, true",
            // -fpsmax 60 caps 120 fps and keeps 60 and 29.97 fps sources at their own rate.
            "FPS_60_MP4, 640, 360, 60/1, true",
            "FPS_120_MP4, 640, 360, 60/1, true",
            "FPS_29_97_MP4, 640, 360, 30000/1001, true",
            "SCENE_CUTS_MP4, 640, 360, 30/1, true"})
    void everyValidFamilyEncodesToTheD27Profile(MediaFixture fixture, int width, int height, String frameRate,
                                                boolean audio) throws IOException {
        Encoded encoded = encoded(fixture);
        MediaMetadata metadata = encoded.metadata();
        HlsOutput output = encoded.output();

        assertThat(HlsOutputCheck.d27Violations(output.directory(), seconds(metadata.duration()))).isEmpty();
        HlsPlaylist playlist = HlsPlaylist.parse(Files.readString(output.playlist(), StandardCharsets.UTF_8));
        assertThat(playlist.segments()).hasSize(output.segments().size());
        assertThat(HlsOutputCheck.segmentDurationViolations(playlist, SEGMENT_SECONDS, SEGMENT_TOLERANCE_SECONDS)).isEmpty();
        assertThat(seconds(output.playlistLength())).isCloseTo(seconds(metadata.duration()), within(1.0));
        assertThat(output.segments()).hasSize((int) Math.ceil(MediaFixtures.sourceSeconds(fixture) / SEGMENT_SECONDS));

        // The box is HLS_TARGET_HEIGHT on the short side and 16/9 of it on the long side, in display orientation.
        boolean portrait = metadata.height() > metadata.width();
        int boxWidth = portrait ? 720 : 1280;
        int boxHeight = portrait ? 1280 : 720;
        for (Path segment : output.segments()) {
            List<JsonNode> streams = streams(segment);
            JsonNode video = streams.getFirst();
            assertThat(List.of(video.get("codec_type").asString(), video.get("codec_name").asString(),
                    video.get("profile").asString(), video.get("pix_fmt").asString()))
                    .as(segment.getFileName().toString()).containsExactly("video", "h264", "High", "yuv420p");
            // The three colour fields are BT.709 on every output, the PQ/BT.2020 HDR source included.
            assertThat(List.of(video.get("color_primaries").asString(), video.get("color_transfer").asString(),
                    video.get("color_space").asString())).containsExactly("bt709", "bt709", "bt709");
            int outputWidth = video.get("width").asInt();
            int outputHeight = video.get("height").asInt();
            assertThat(List.of(outputWidth, outputHeight)).containsExactly(width, height);
            assertThat(outputWidth % 2 + outputHeight % 2).isZero();
            assertThat(outputWidth).isLessThanOrEqualTo(Math.min(boxWidth, metadata.width()));
            assertThat(outputHeight).isLessThanOrEqualTo(Math.min(boxHeight, metadata.height()));
            assertThat(video.get("avg_frame_rate").asString()).isEqualTo(frameRate);
            if (audio) {
                JsonNode sound = streams.get(1);
                assertThat(List.of(sound.get("codec_name").asString(), sound.get("profile").asString(),
                        sound.get("sample_rate").asString(), sound.get("channels").asInt()))
                        .containsExactly("aac", "LC", "48000", 2);
                assertThat(streams).hasSize(2);
            } else {
                assertThat(streams).hasSize(1);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // The transform that turns the coded frames into the expected display: a Display Matrix angle counts
            // counterclockwise, the legacy rotate tag clockwise (FfprobeOutput).
            "H264_AAC_MP4, null",
            "ROTATED_90_MP4, transpose=cclock",
            "ROTATED_MINUS_90_MKV, transpose=clock",
            "ROTATED_180_MP4, 'hflip,vflip'",
            "ROTATE_TAG_90_MKV, transpose=clock"})
    void rotatedSourcesComeOutInTheirDisplayOrientation(MediaFixture fixture, String expected) {
        Encoded encoded = encoded(fixture);
        boolean quarterTurn = expected.startsWith("transpose");
        List<String> candidates = quarterTurn ? List.of("transpose=cclock", "transpose=clock") : List.of("null", "hflip,vflip");
        assertThat(candidates).contains(expected);

        // The output matches the expected transform of the coded source, and clearly not the opposite one.
        double match = psnr(encoded, expected);
        for (String other : candidates) {
            if (other.equals(expected)) continue;
            double mismatch = psnr(encoded, other);
            assertThat(match).as("%s: PSNR of %s against %s", fixture, expected, other).isGreaterThan(mismatch + 10);
        }
        assertThat(match).as(fixture.name()).isGreaterThan(20);
    }

    @Test
    void verticalReflectionInTheDisplayMatrixIsAppliedToOutputPixels() {
        Encoded encoded = encoded(MediaFixture.VERTICAL_REFLECTION_MP4);
        assertThat(encoded.metadata().rotation()).isZero();

        double reflected = psnr(encoded, "vflip");
        double unreflected = psnr(encoded, "null");
        assertThat(reflected).as("PSNR against vertically flipped pixels (unreflected PSNR: %.2f dB)", unreflected)
                .isGreaterThan(unreflected + 3)
                .isGreaterThan(20);
    }

    @Test
    void theEncodedHdrSourceIsEightBitBt709() throws IOException {
        // The source keeps PQ/BT.2020 on both the frames and the stream; output options alone would keep them.
        JsonNode source = tools.probeJson(fixtures.path(HEVC_MAIN10_HDR_AC3_MKV), HEVC_MAIN10_HDR_AC3_MKV.demuxer())
                .get("streams").get(0);
        assertThat(List.of(source.get("color_primaries").asString(), source.get("color_transfer").asString(),
                source.get("pix_fmt").asString())).containsExactly("bt2020", "smpte2084", "yuv420p10le");

        for (Path segment : encoded(HEVC_MAIN10_HDR_AC3_MKV).output().segments()) {
            JsonNode video = streams(segment).getFirst();
            assertThat(List.of(video.get("pix_fmt").asString(), video.get("color_primaries").asString(),
                    video.get("color_transfer").asString(), video.get("color_space").asString()))
                    .containsExactly("yuv420p", "bt709", "bt709", "bt709");
            // Frames carry the tags too, not only the stream header.
            JsonNode frame = firstFrame(segment);
            assertThat(List.of(frame.get("color_primaries").asString(), frame.get("color_transfer").asString(),
                    frame.get("color_space").asString())).containsExactly("bt709", "bt709", "bt709");
        }
    }

    @Test
    void aFaststartFileTruncatedToThirtyPercentFailsTheEncodeAndTheValidation() throws IOException {
        Path job = job();
        Path input = raw(job, TRUNCATED_FASTSTART_MP4);
        // The moov box is intact, so the probe accepts the full 13 s.
        MediaMetadata metadata = probe(input, TRUNCATED_FASTSTART_MP4);
        assertThat(seconds(metadata.duration())).isCloseTo(MediaFixtures.SOURCE_SECONDS, within(0.1));

        assertCode(() -> service(policy()).transcode(job, input, "mp4", metadata), ENCODE_FAILED);

        // FFmpeg leaves a playlist that still ends with #EXT-X-ENDLIST; the D27 length check rejects it on its own.
        Path hls = job.resolve(FfmpegHlsService.HLS_DIRECTORY);
        HlsPlaylist playlist = HlsPlaylist.parse(Files.readString(hls.resolve("master.m3u8"), StandardCharsets.UTF_8));
        assertThat(playlist.endList()).isTrue();
        assertThat(playlist.totalSeconds()).isLessThan(seconds(metadata.duration()) - 1);
        assertThatThrownBy(() -> HlsOutputValidator.validate(hls, metadata.duration()))
                .isInstanceOfSatisfying(WorkerFailureException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ENCODE_FAILED))
                .hasMessageContaining("within 1 second");
    }

    @Test
    void coverArtOnlyInputFails() throws IOException {
        Path job = job();
        Path input = raw(job, COVER_ART_ONLY_MP4);
        // The probe rejects this file first (UNSUPPORTED_MEDIA); here FFmpeg itself must not turn the attached
        // picture into a video: 0:V:0 matches no stream that is not an attached picture.
        MediaMetadata metadata = new MediaMetadata(Duration.ofSeconds(13), 320, 320, 0, false, "png", null);

        assertCode(() -> service(policy()).transcode(job, input, "mp4", metadata), ENCODE_FAILED);
        assertThat(job.resolve(FfmpegHlsService.HLS_DIRECTORY).resolve("segments")).isEmptyDirectory();
    }

    @Test
    void theDeadlineKillsFfmpegAndYieldsEncodeTimeout() throws IOException {
        // A tiny test deadline: max(1 s, 0.001 x 7 s) = 1 s, far less than a 1080p30 encode at preset medium needs.
        TranscodeSettings tiny = settings("1", "0.001");
        Path job = job();
        Path input = raw(job, H264_AAC_1080P30_MP4);
        MediaMetadata metadata = probe(input, H264_AAC_1080P30_MP4);
        assertThat(tiny.deadline(metadata.duration())).isEqualTo(Duration.ofSeconds(1));
        long started = System.nanoTime();

        assertCode(() -> service(tiny).transcode(job, input, "mp4", metadata), ENCODE_TIMEOUT);

        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(15));
        // No FFmpeg of this job is left running.
        assertThat(ProcessHandle.allProcesses().filter(process -> process.info().commandLine()
                .map(line -> line.contains(job.toString())).orElse(false)).toList()).isEmpty();
    }

    /** Encodes each fixture once: the profile and the orientation tests share the output. */
    private static synchronized Encoded encoded(MediaFixture fixture) {
        Encoded existing = ENCODED.get(fixture);
        if (existing != null) return existing;
        try {
            Path job = job();
            Path input = raw(job, fixture);
            MediaMetadata metadata = probe(input, fixture);
            HlsOutput output = service(policy()).transcode(job, input, extension(fixture), metadata);
            Encoded encoded = new Encoded(fixture, input, metadata, output);
            ENCODED.put(fixture, encoded);
            return encoded;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Compares coded pixels with autorotation disabled on both inputs, applying only {@code transform} to the source. */
    private static double psnr(Encoded encoded, String transform) {
        MediaTools.Result result = tools.ffmpeg(List.of("-nostdin", "-hide_banner", "-loglevel", "info",
                "-protocol_whitelist", "file", "-noautorotate", "-f", "mpegts",
                "-i", "file:" + encoded.output().segments().getFirst(),
                "-noautorotate", "-f", encoded.fixture().demuxer().ffmpegName(), "-i", "file:" + encoded.input(),
                "-lavfi", "[0:v]setpts=PTS-STARTPTS[out];[1:v]" + transform + ",setpts=PTS-STARTPTS[source];"
                        + "[out][source]psnr", "-frames:v", "10", "-f", "null", "-"));
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        Matcher matcher = PSNR_AVERAGE.matcher(result.stderr());
        assertThat(matcher.find()).as(result.stderr()).isTrue();
        return matcher.group(1).equals("inf") ? Double.POSITIVE_INFINITY : Double.parseDouble(matcher.group(1));
    }

    private static List<JsonNode> streams(Path segment) {
        MediaTools.Result result = tools.ffprobe(List.of("-hide_banner", "-loglevel", "error",
                "-protocol_whitelist", "file", "-f", "mpegts", "-show_streams", "-of", "json", "file:" + segment));
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        List<JsonNode> streams = new ArrayList<>();
        JSON.readTree(result.stdout()).get("streams").forEach(streams::add);
        return streams;
    }

    private static JsonNode firstFrame(Path segment) {
        MediaTools.Result result = tools.ffprobe(List.of("-hide_banner", "-loglevel", "error",
                "-protocol_whitelist", "file", "-f", "mpegts", "-select_streams", "v:0", "-read_intervals", "%+#1",
                "-show_frames", "-of", "json", "file:" + segment));
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        return JSON.readTree(result.stdout()).get("frames").get(0);
    }

    private static Path job() throws IOException {
        return Files.createDirectory(directory.resolve("job-" + JOBS.incrementAndGet()));
    }

    /** Copies the fixture into the job directory as the raw copy, named by its trusted key extension. */
    private static Path raw(Path job, MediaFixture fixture) throws IOException {
        return Files.copy(fixtures.path(fixture), job.resolve("raw." + extension(fixture)));
    }

    private static MediaMetadata probe(Path input, MediaFixture fixture) {
        ProbeSettings caps = new ProbeSettings(System.getenv(ProbeSettings.FFPROBE_PATH), 30, 10_800, 1920, 1080, 8);
        return new FfprobeService(caps, new MediaProcessRunner()).probe(input, extension(fixture));
    }

    /** The D27/D28 policy values; a generous test deadline (#124 measures the real one). */
    private static TranscodeSettings policy() {
        return settings("120", "10");
    }

    /** Read as the worker reads them, so FFMPEG_PATH comes from the environment like in the worker image. */
    private static TranscodeSettings settings(String timeoutMinSeconds, String timeoutFactor) {
        Map<String, String> variables = Map.of(
                TranscodeSettings.FFMPEG_PATH, System.getenv(TranscodeSettings.FFMPEG_PATH),
                TranscodeSettings.FFMPEG_TIMEOUT_MIN_SECONDS, timeoutMinSeconds,
                TranscodeSettings.FFMPEG_TIMEOUT_FACTOR, timeoutFactor,
                TranscodeSettings.SEGMENT_SECONDS, String.valueOf(SEGMENT_SECONDS),
                TranscodeSettings.TARGET_HEIGHT, "720",
                TranscodeSettings.VIDEO_MAXRATE_KBPS, "4000",
                TranscodeSettings.FFMPEG_THREADS, "1",
                TranscodeSettings.MAX_STREAMS, "8");
        return TranscodeSettings.from(variables::get);
    }

    private static FfmpegHlsService service(TranscodeSettings settings) {
        return new FfmpegHlsService(settings, new MediaProcessRunner());
    }

    private static String extension(MediaFixture fixture) {
        return fixture.fileName().substring(fixture.fileName().lastIndexOf('.') + 1);
    }

    private static double seconds(Duration duration) {
        return duration.toNanos() / 1e9;
    }

    private static void assertCode(ThrowingCallable call, WorkerFailureCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(WorkerFailureException.class,
                failure -> assertThat(failure.code()).isEqualTo(code));
    }

    private record Encoded(MediaFixture fixture, Path input, MediaMetadata metadata, HlsOutput output) {
    }
}
