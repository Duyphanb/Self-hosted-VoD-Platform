package com.vodplatform.worker.probe;

import static com.vodplatform.worker.probe.WorkerFailureCode.MEDIA_LIMIT_EXCEEDED;
import static com.vodplatform.worker.probe.WorkerFailureCode.PROBE_FAILED;
import static com.vodplatform.worker.probe.WorkerFailureCode.PROBE_TIMEOUT;
import static com.vodplatform.worker.probe.WorkerFailureCode.UNSUPPORTED_MEDIA;
import static com.vodplatform.worker.probe.WorkerFailureCode.WORKER_INTERRUPTED;
import static com.vodplatform.worker.testsupport.MediaFixture.AUDIO_ONLY_MP4;
import static com.vodplatform.worker.testsupport.MediaFixture.COVER_ART_ONLY_MP4;
import static com.vodplatform.worker.testsupport.MediaFixture.EBML_UNDER_MP4_KEY;
import static com.vodplatform.worker.testsupport.MediaFixture.FFCONCAT_UNDER_MKV_KEY;
import static com.vodplatform.worker.testsupport.MediaFixture.H264_AAC_MP4;
import static com.vodplatform.worker.testsupport.MediaFixture.HLS_PLAYLIST_UNDER_MP4_KEY;
import static com.vodplatform.worker.testsupport.MediaFixture.MP2_AUDIO_MKV;
import static com.vodplatform.worker.testsupport.MediaFixture.MPEG2_VIDEO_MKV;
import static com.vodplatform.worker.testsupport.MediaFixture.NINE_STREAMS_MKV;
import static com.vodplatform.worker.testsupport.MediaFixture.NINE_STREAMS_MP4;
import static com.vodplatform.worker.testsupport.MediaFixture.OVER_CAP_2560X1440_MP4;
import static com.vodplatform.worker.testsupport.MediaFixture.PORTRAIT_H264_AAC_MP4;
import static com.vodplatform.worker.testsupport.MediaFixture.ROTATED_45_MP4;
import static com.vodplatform.worker.testsupport.MediaFixture.ROTATED_90_MP4;
import static com.vodplatform.worker.testsupport.MediaFixture.TEXT_UNDER_MP4_KEY;
import static com.vodplatform.worker.testsupport.MediaFixture.TRUNCATED_MOOV_AT_END_MP4;
import static com.vodplatform.worker.testsupport.MediaFixture.VIDEO_WITH_COVER_ART_MP4;
import static com.vodplatform.worker.testsupport.MediaFixture.VP9_OPUS_WEBM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.vodplatform.worker.probe.process.MediaProcessRunner;
import com.vodplatform.worker.testsupport.FakeMediaTool;
import com.vodplatform.worker.testsupport.MediaFixture;
import com.vodplatform.worker.testsupport.MediaFixtures;
import com.vodplatform.worker.testsupport.MediaTools;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The FFprobe service on the #123 generated fixtures with the ADR-010 D25 binary (the worker image's
 * FFPROBE_PATH). Caps are the ADR-010 D26 policy; the 30 s timeout is only a test value (#124 measures it).
 */
@Tag(MediaTools.FFMPEG_TAG)
class FfprobeServiceFfmpegTests {
    private static final Duration GENEROUS = Duration.ofSeconds(60);
    @TempDir
    static Path directory;
    private static MediaFixtures fixtures;
    private static String ffprobe;

    @BeforeAll
    static void fixtures() {
        fixtures = new MediaFixtures(MediaTools.fromEnvironment(), directory.resolve("fixtures"));
        ffprobe = System.getenv("FFPROBE_PATH");
    }

    @ParameterizedTest
    @CsvSource({"H264_AAC_MP4, h264, 640, 360, 0", "VP9_OPUS_WEBM, vp9, 640, 360, 0", "H264_HIGH10_FLAC_MKV, h264, 640, 360, 0",
            "PORTRAIT_H264_AAC_MP4, h264, 360, 640, 0", "SILENT_H264_MP4, h264, 640, 360, 0",
            "HEVC_MAIN10_HDR_AC3_MKV, hevc, 640, 360, 0",
            // Rotation: a 90/270-degree Display Matrix swaps the display size; -90 is 270 and -180 is 180.
            "ROTATED_90_MP4, h264, 360, 640, 90", "ROTATED_MINUS_90_MKV, h264, 360, 640, 270",
            "ROTATED_180_MP4, h264, 640, 360, 180", "ROTATE_TAG_90_MKV, h264, 360, 640, 90"})
    void validFamiliesProbeToTheirMetadata(MediaFixture fixture, String codec, int width, int height, int rotation) {
        MediaMetadata metadata = probe(policy(), fixture);

        assertThat(List.of(metadata.codec(), metadata.width(), metadata.height(), metadata.rotation()))
                .containsExactly(codec, width, height, rotation);
        double seconds = metadata.duration().toNanos() / 1e9;
        assertThat(seconds).isCloseTo(MediaFixtures.SOURCE_SECONDS, within(0.1));
        assertThat(metadata.durationSeconds()).isEqualTo((int) Math.ceil(seconds));
        assertThat(metadata.bitrate()).isPositive();
    }

    @Test
    void keyContentMismatchTruncationAndDisguisedPlaylistsFailToProbe() {
        for (MediaFixture fixture : List.of(EBML_UNDER_MP4_KEY, TEXT_UNDER_MP4_KEY, HLS_PLAYLIST_UNDER_MP4_KEY,
                FFCONCAT_UNDER_MKV_KEY, TRUNCATED_MOOV_AT_END_MP4)) {
            assertCode(() -> probe(policy(), fixture), PROBE_FAILED, fixture);
        }
        // The other direction of a family mismatch: ISO-BMFF bytes under a Matroska key.
        assertCode(() -> service(policy()).probe(fixtures.path(H264_AAC_MP4), "mkv"), PROBE_FAILED, H264_AAC_MP4);
        assertThat(fixtures.canary()).doesNotExist();
    }

    @Test
    void audioOnlyAndCoverArtOnlyHaveNoVideoToEncode() {
        assertCode(() -> probe(policy(), AUDIO_ONLY_MP4), UNSUPPORTED_MEDIA, AUDIO_ONLY_MP4);
        // FFprobe already fails here: the PNG cover art needs a decoder outside the D26 allowlist.
        assertCode(() -> probe(policy(), COVER_ART_ONLY_MP4), UNSUPPORTED_MEDIA, COVER_ART_ONLY_MP4);
    }

    @Test
    void coverArtBesideARealVideoStillNeedsADecoderOutsideTheAllowlist() {
        // FFprobe opens a decoder for every stream, so the D26 list rejects the file before cover-art selection applies.
        assertCode(() -> probe(policy(), VIDEO_WITH_COVER_ART_MP4), UNSUPPORTED_MEDIA, VIDEO_WITH_COVER_ART_MP4);
    }

    @Test
    void anyStreamNeedingADecoderOutsideTheAllowlistIsUnsupported() {
        assertCode(() -> probe(policy(), MPEG2_VIDEO_MKV), UNSUPPORTED_MEDIA, MPEG2_VIDEO_MKV);
        assertCode(() -> probe(policy(), MP2_AUDIO_MKV), UNSUPPORTED_MEDIA, MP2_AUDIO_MKV);
        assertCode(() -> probe(policy(), ROTATED_45_MP4), UNSUPPORTED_MEDIA, ROTATED_45_MP4);
    }

    @Test
    void nineStreamsExceedTheCapInMatroskaAsInMov() {
        // FFmpeg 7.1 drops the ninth Matroska track and exits 0; the demuxer's max_streams report still fails it.
        assertCode(() -> probe(policy(), NINE_STREAMS_MKV), MEDIA_LIMIT_EXCEEDED, NINE_STREAMS_MKV);
        assertCode(() -> probe(policy(), NINE_STREAMS_MP4), MEDIA_LIMIT_EXCEEDED, NINE_STREAMS_MP4);
        // With room for nine streams both files are otherwise acceptable, so the stream cap is the only cause.
        ProbeSettings nineStreams = new ProbeSettings(ffprobe, 30, 10_800, 1920, 1080, 9);
        assertThat(probe(nineStreams, NINE_STREAMS_MKV).codec()).isEqualTo("h264");
        assertThat(probe(nineStreams, NINE_STREAMS_MP4).codec()).isEqualTo("h264");
    }

    @Test
    void sizeAndDurationCapsApplyInEitherOrientation() {
        assertCode(() -> probe(policy(), OVER_CAP_2560X1440_MP4), MEDIA_LIMIT_EXCEEDED, OVER_CAP_2560X1440_MP4);
        ProbeSettings exact = new ProbeSettings(ffprobe, 30, 13, 640, 360, 8);
        assertThat(probe(exact, H264_AAC_MP4).width()).isEqualTo(640);
        assertThat(probe(exact, PORTRAIT_H264_AAC_MP4).height()).isEqualTo(640);
        assertThat(probe(exact, ROTATED_90_MP4).height()).isEqualTo(640);
        assertCode(() -> probe(new ProbeSettings(ffprobe, 30, 13, 639, 360, 8), H264_AAC_MP4), MEDIA_LIMIT_EXCEEDED, H264_AAC_MP4);
        assertCode(() -> probe(new ProbeSettings(ffprobe, 30, 13, 640, 359, 8), PORTRAIT_H264_AAC_MP4), MEDIA_LIMIT_EXCEEDED,
                PORTRAIT_H264_AAC_MP4);
        assertCode(() -> probe(new ProbeSettings(ffprobe, 30, 12, 640, 360, 8), H264_AAC_MP4), MEDIA_LIMIT_EXCEEDED, H264_AAC_MP4);
        // WebM's format duration is a little over 13 s, so it rounds up past a 13 s cap.
        assertThat(probe(new ProbeSettings(ffprobe, 30, 14, 640, 360, 8), VP9_OPUS_WEBM).durationSeconds()).isEqualTo(14);
    }

    @Test
    void deadlineKillsAFakeFfprobeAndTheChildItSpawned() throws Exception {
        Path tool = Files.createDirectories(directory.resolve("timeout"));
        ProbeSettings settings = new ProbeSettings(FakeMediaTool.stallingScript(tool).toString(), 2, 10_800, 1920, 1080, 8);
        long started = System.nanoTime();
        CompletableFuture<MediaMetadata> probe = CompletableFuture.supplyAsync(() -> probe(settings, H264_AAC_MP4));
        List<ProcessHandle> tree = handles(FakeMediaTool.awaitPids(tool, GENEROUS));

        assertThatThrownBy(() -> probe.get(60, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .cause().isInstanceOfSatisfying(WorkerFailureException.class,
                        failure -> assertThat(failure.code()).isEqualTo(PROBE_TIMEOUT));
        assertThat(tree).noneMatch(ProcessHandle::isAlive);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(15));
    }

    @Test
    void interruptKillsAFakeFfprobeAndKeepsTheInterruptStatus() throws Exception {
        Path tool = Files.createDirectories(directory.resolve("interrupt"));
        ProbeSettings settings = new ProbeSettings(FakeMediaTool.stallingScript(tool).toString(), 600, 10_800, 1920, 1080, 8);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interruptKept = new AtomicBoolean();
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                probe(settings, H264_AAC_MP4);
            } catch (Throwable e) {
                thrown.set(e);
                interruptKept.set(Thread.currentThread().isInterrupted());
            }
        });
        List<ProcessHandle> tree = handles(FakeMediaTool.awaitPids(tool, GENEROUS));

        caller.interrupt();

        assertThat(caller.join(GENEROUS)).isTrue();
        assertThat(thrown.get()).isInstanceOfSatisfying(WorkerFailureException.class,
                failure -> assertThat(failure.code()).isEqualTo(WORKER_INTERRUPTED));
        assertThat(interruptKept).isTrue();
        assertThat(tree).noneMatch(ProcessHandle::isAlive);
    }

    private static ProbeSettings policy() {
        return new ProbeSettings(ffprobe, 30, 10_800, 1920, 1080, 8);
    }

    private static FfprobeService service(ProbeSettings settings) {
        return new FfprobeService(settings, new MediaProcessRunner());
    }

    private static MediaMetadata probe(ProbeSettings settings, MediaFixture fixture) {
        String name = fixture.fileName();
        return service(settings).probe(fixtures.path(fixture), name.substring(name.lastIndexOf('.') + 1));
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, WorkerFailureCode code,
                                   MediaFixture fixture) {
        assertThatThrownBy(call).as(fixture.name()).isInstanceOfSatisfying(WorkerFailureException.class,
                failure -> assertThat(failure.code()).isEqualTo(code));
    }

    private static List<ProcessHandle> handles(List<Long> pids) {
        return pids.stream().map(pid -> ProcessHandle.of(pid).orElseThrow()).toList();
    }
}
