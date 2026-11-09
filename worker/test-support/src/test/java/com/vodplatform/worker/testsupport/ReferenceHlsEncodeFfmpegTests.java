package com.vodplatform.worker.testsupport;

import static com.vodplatform.worker.testsupport.MediaFixture.HEVC_MAIN10_HDR_AC3_MKV;
import static com.vodplatform.worker.testsupport.MediaFixture.SILENT_H264_MP4;
import static com.vodplatform.worker.testsupport.MediaFixture.TRUNCATED_FASTSTART_MP4;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the ADR-010 D27 profile over the generated valid corpus with the worker image binary. This is the
 * evidence D27 asks for before Issue 5.5 merges: neither {@code -xerror} nor the 1-second EXTINF tolerance
 * rejects a valid file, while a truncated faststart file is rejected.
 */
@Tag(MediaTools.FFMPEG_TAG)
class ReferenceHlsEncodeFfmpegTests {
    private static final int SEGMENT_SECONDS = 6;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @TempDir
    static Path directory;
    private static MediaTools tools;
    private static MediaFixtures fixtures;

    @BeforeAll
    static void tools() {
        tools = MediaTools.fromEnvironment();
        fixtures = new MediaFixtures(tools, directory.resolve("fixtures"));
    }

    @ParameterizedTest
    @EnumSource(value = MediaFixture.class, names = {"H264_AAC_MP4", "VP9_OPUS_WEBM", "H264_HIGH10_FLAC_MKV",
            "PORTRAIT_H264_AAC_MP4", "SILENT_H264_MP4", "HEVC_MAIN10_HDR_AC3_MKV"})
    void validCorpusPassesTheD27OutputChecks(MediaFixture fixture) throws IOException {
        Path input = fixtures.path(fixture);
        JsonNode source = tools.probeJson(input, fixture.demuxer());
        JsonNode sourceVideo = selectedVideo(source);
        Path hls = directory.resolve("hls-" + fixture.name());

        MediaTools.Result result = encode(input, fixture.demuxer(), hls, sourceVideo);

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(HlsOutputCheck.d27Violations(hls, mediaDuration(source))).isEmpty();
        HlsPlaylist playlist = playlist(hls);
        assertThat(playlist.segments()).hasSize(3);
        assertThat(HlsOutputCheck.segmentDurationViolations(playlist, SEGMENT_SECONDS, 0.1)).isEmpty();

        JsonNode output = probeSegment(hls.resolve(playlist.segments().getFirst().uri()));
        JsonNode video = output.get("streams").get(0);
        assertThat(video.get("codec_name").asString()).isEqualTo("h264");
        assertThat(video.get("profile").asString()).isEqualTo("High");
        assertThat(video.get("pix_fmt").asString()).isEqualTo("yuv420p");
        assertThat(List.of(video.get("color_primaries").asString(), video.get("color_transfer").asString(),
                video.get("color_space").asString())).containsOnly("bt709");
        int width = video.get("width").asInt();
        int height = video.get("height").asInt();
        assertThat(width % 2 + height % 2).isZero();
        assertThat(width).isLessThanOrEqualTo(Math.min(box(sourceVideo)[0], sourceVideo.get("width").asInt()));
        assertThat(height).isLessThanOrEqualTo(Math.min(box(sourceVideo)[1], sourceVideo.get("height").asInt()));
        if (fixture == SILENT_H264_MP4) {
            assertThat(output.get("streams")).hasSize(1);
        } else {
            JsonNode audio = output.get("streams").get(1);
            assertThat(List.of(audio.get("codec_name").asString(), audio.get("profile").asString(),
                    audio.get("sample_rate").asString(), audio.get("channels").asString()))
                    .containsExactly("aac", "LC", "48000", "2");
        }
    }

    @Test
    void truncatedFaststartIsRejectedByExitStatusAndByTheDurationCheck() throws IOException {
        Path input = fixtures.path(TRUNCATED_FASTSTART_MP4);
        JsonNode source = tools.probeJson(input, TRUNCATED_FASTSTART_MP4.demuxer());
        Path hls = directory.resolve("hls-truncated");

        MediaTools.Result result = encode(input, TRUNCATED_FASTSTART_MP4.demuxer(), hls, selectedVideo(source));

        assertThat(result.exitCode()).isNotZero();
        // FFmpeg still leaves a VOD playlist that ends with #EXT-X-ENDLIST; only the duration check sees the loss.
        assertThat(playlist(hls).endList()).isTrue();
        assertThat(HlsOutputCheck.d27Violations(hls, mediaDuration(source)))
                .singleElement().asString().startsWith("EXTINF sum");
    }

    @Test
    void colourOptionsAloneKeepHdrTagsOnSdrOutput() throws IOException {
        // FFmpeg 7.1: output -color_primaries/-color_trc do not override the PQ/BT.2020 frame tags, so 8-bit
        // output would still claim HDR. The reference command therefore retags with setparams (Issue 5.5).
        Path output = directory.resolve("colour-options-only.ts");
        MediaTools.Result result = tools.ffmpeg(List.of("-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                "-protocol_whitelist", "file", "-f", HEVC_MAIN10_HDR_AC3_MKV.demuxer().ffmpegName(),
                "-i", "file:" + fixtures.path(HEVC_MAIN10_HDR_AC3_MKV), "-map", "0:V:0", "-t", "1",
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709", "-f", "mpegts", output.toString()));

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        JsonNode video = probeSegment(output).get("streams").get(0);
        assertThat(video.get("pix_fmt").asString()).isEqualTo("yuv420p");
        assertThat(video.get("color_transfer").asString()).isEqualTo("smpte2084");
        assertThat(video.get("color_primaries").asString()).isEqualTo("bt2020");
    }

    /**
     * The Issue 5.5 argument list, with BT.709 written by setparams (see the HDR test). Issue 5.5 replaces
     * this reference with the production command builder; the D26 -codec_whitelist arrives with Issue 5.4.
     */
    static List<String> referenceArguments(Path input, MediaFixture.Demuxer demuxer, Path hls, int boxWidth, int boxHeight) {
        return List.of("-nostdin", "-hide_banner", "-xerror", "-nostats", "-loglevel", "error",
                "-progress", "pipe:1", "-stats_period", "1",
                "-protocol_whitelist", "file", "-max_streams", "8",
                "-f", demuxer.ffmpegName(), "-i", "file:" + input.toAbsolutePath(),
                "-map", "0:V:0", "-map", "0:a:0?", "-map_metadata", "-1", "-map_chapters", "-1",
                "-vf", "scale=w=min(" + boxWidth + "\\,iw):h=min(" + boxHeight + "\\,ih)"
                        + ":force_original_aspect_ratio=decrease:force_divisible_by=2,"
                        + "setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709",
                "-c:v", "libx264", "-profile:v", "high", "-pix_fmt", "yuv420p", "-preset", "medium", "-crf", "23",
                "-maxrate:v", "4000k", "-bufsize:v", "8000k",
                "-fpsmax", "60", "-force_key_frames", "expr:gte(t,n_forced*" + SEGMENT_SECONDS + ")",
                "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709",
                "-c:a", "aac", "-profile:a", "aac_low", "-b:a", "128k", "-ac", "2", "-ar", "48000",
                "-threads", "1", "-filter_threads", "1",
                "-f", "hls", "-hls_time", String.valueOf(SEGMENT_SECONDS), "-hls_playlist_type", "vod",
                "-hls_list_size", "0", "-hls_segment_type", "mpegts", "-hls_flags", "independent_segments",
                "-hls_base_url", "segments/", "-hls_segment_filename", hls.resolve("segments/seg_%05d.ts").toString(),
                hls.resolve(HlsOutputCheck.PLAYLIST).toString());
    }

    private static MediaTools.Result encode(Path input, MediaFixture.Demuxer demuxer, Path hls, JsonNode sourceVideo)
            throws IOException {
        Files.createDirectories(hls.resolve("segments"));
        int[] box = box(sourceVideo);
        return tools.ffmpeg(referenceArguments(input, demuxer, hls, box[0], box[1]));
    }

    /** 1280x720 in display orientation (720x1280 for portrait); the six fixtures encoded here carry no rotation. */
    private static int[] box(JsonNode video) {
        return video.get("width").asInt() >= video.get("height").asInt() ? new int[] {1280, 720} : new int[] {720, 1280};
    }

    /** ADR-010 D26: the first non-attached-picture video stream. */
    private static JsonNode selectedVideo(JsonNode probe) {
        for (JsonNode stream : probe.get("streams")) {
            if ("video".equals(stream.get("codec_type").asString())
                    && stream.get("disposition").get("attached_pic").asInt() == 0) return stream;
        }
        throw new AssertionError("no video stream");
    }

    /** ADR-010 D26: the selected stream's duration, or the format duration when the stream has none. */
    private static double mediaDuration(JsonNode probe) {
        JsonNode duration = selectedVideo(probe).get("duration");
        if (duration == null) duration = probe.get("format").get("duration");
        return Double.parseDouble(duration.asString());
    }

    private static HlsPlaylist playlist(Path hls) throws IOException {
        return HlsPlaylist.parse(Files.readString(hls.resolve(HlsOutputCheck.PLAYLIST), StandardCharsets.UTF_8));
    }

    private static JsonNode probeSegment(Path segment) {
        MediaTools.Result result = tools.ffprobe(new ArrayList<>(List.of("-hide_banner", "-loglevel", "error",
                "-protocol_whitelist", "file", "-f", "mpegts", "-show_streams", "-of", "json", "file:" + segment)));
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        return JSON.readTree(result.stdout());
    }
}
