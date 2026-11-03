package com.vodplatform.worker.testsupport;

import static com.vodplatform.worker.testsupport.MediaFixture.AUDIO_ONLY_MP4;
import static com.vodplatform.worker.testsupport.MediaFixture.COVER_ART_ONLY_MP4;
import static com.vodplatform.worker.testsupport.MediaFixture.EBML_UNDER_MP4_KEY;
import static com.vodplatform.worker.testsupport.MediaFixture.FFCONCAT_UNDER_MKV_KEY;
import static com.vodplatform.worker.testsupport.MediaFixture.H264_AAC_MP4;
import static com.vodplatform.worker.testsupport.MediaFixture.H264_HIGH10_FLAC_MKV;
import static com.vodplatform.worker.testsupport.MediaFixture.HEVC_MAIN10_HDR_AC3_MKV;
import static com.vodplatform.worker.testsupport.MediaFixture.HLS_PLAYLIST_UNDER_MP4_KEY;
import static com.vodplatform.worker.testsupport.MediaFixture.MP2_AUDIO_MKV;
import static com.vodplatform.worker.testsupport.MediaFixture.MPEG2_VIDEO_MKV;
import static com.vodplatform.worker.testsupport.MediaFixture.NINE_STREAMS_MKV;
import static com.vodplatform.worker.testsupport.MediaFixture.NINE_STREAMS_MP4;
import static com.vodplatform.worker.testsupport.MediaFixture.OVER_CAP_2560X1440_MP4;
import static com.vodplatform.worker.testsupport.MediaFixture.PORTRAIT_H264_AAC_MP4;
import static com.vodplatform.worker.testsupport.MediaFixture.ROTATED_180_MP4;
import static com.vodplatform.worker.testsupport.MediaFixture.ROTATED_45_MP4;
import static com.vodplatform.worker.testsupport.MediaFixture.ROTATED_90_MP4;
import static com.vodplatform.worker.testsupport.MediaFixture.ROTATED_MINUS_90_MKV;
import static com.vodplatform.worker.testsupport.MediaFixture.ROTATE_TAG_90_MKV;
import static com.vodplatform.worker.testsupport.MediaFixture.SILENT_H264_MP4;
import static com.vodplatform.worker.testsupport.MediaFixture.TEXT_UNDER_MP4_KEY;
import static com.vodplatform.worker.testsupport.MediaFixture.TRUNCATED_FASTSTART_MP4;
import static com.vodplatform.worker.testsupport.MediaFixture.TRUNCATED_MOOV_AT_END_MP4;
import static com.vodplatform.worker.testsupport.MediaFixture.VIDEO_WITH_COVER_ART_MP4;
import static com.vodplatform.worker.testsupport.MediaFixture.VP9_OPUS_WEBM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Each generated fixture is what it claims to be when probed as ADR-010 requires (forced demuxer, file protocol). */
@Tag(MediaTools.FFMPEG_TAG)
class GeneratedFixturesFfmpegTests {
    @TempDir
    static Path directory;
    private static MediaTools tools;
    private static MediaFixtures fixtures;

    @BeforeAll
    static void tools() {
        tools = MediaTools.fromEnvironment();
        fixtures = new MediaFixtures(tools, directory);
    }

    @Test
    void validSourcesHaveTheDeclaredStreams() {
        assertStreams(H264_AAC_MP4, "video h264 High yuv420p 640x360", "audio aac 2ch");
        assertStreams(VP9_OPUS_WEBM, "video vp9 Profile 0 yuv420p 640x360", "audio opus 2ch");
        assertStreams(H264_HIGH10_FLAC_MKV, "video h264 High 10 yuv420p10le 640x360", "audio flac 2ch");
        assertStreams(PORTRAIT_H264_AAC_MP4, "video h264 Constrained Baseline yuv420p 360x640", "audio aac 2ch");
        assertStreams(SILENT_H264_MP4, "video h264 Constrained Baseline yuv420p 640x360");
        assertStreams(HEVC_MAIN10_HDR_AC3_MKV, "video hevc Main 10 yuv420p10le 640x360", "audio ac3 6ch");
        for (MediaFixture rotated : List.of(ROTATED_90_MP4, ROTATED_MINUS_90_MKV, ROTATED_180_MP4, ROTATE_TAG_90_MKV)) {
            // Rotation is metadata only: the coded size stays landscape.
            assertStreams(rotated, "video h264 High yuv420p 640x360", "audio aac 2ch");
        }
        for (MediaFixture fixture : MediaFixture.values()) {
            if (!fixture.valid()) continue;
            JsonNode format = probe(fixture).get("format");
            assertThat(Double.parseDouble(format.get("duration").asString())).as(fixture.name())
                    .isCloseTo(MediaFixtures.SOURCE_SECONDS, within(0.1));
        }
    }

    @Test
    void hdrSourceCarriesPqAndBt2020Tags() {
        JsonNode video = probe(HEVC_MAIN10_HDR_AC3_MKV).get("streams").get(0);

        assertThat(video.get("color_transfer").asString()).isEqualTo("smpte2084");
        assertThat(video.get("color_primaries").asString()).isEqualTo("bt2020");
        assertThat(video.get("color_space").asString()).isEqualTo("bt2020nc");
    }

    @Test
    void rotationFixturesCarryADisplayMatrixOrOnlyTheLegacyTag() {
        assertThat(displayMatrixRotation(ROTATED_90_MP4)).isEqualTo(90);
        assertThat(displayMatrixRotation(ROTATED_MINUS_90_MKV)).isEqualTo(-90);
        // FFprobe reports the angle in [-180, 180]; a 180-degree matrix comes out as -180.
        assertThat(displayMatrixRotation(ROTATED_180_MP4)).isEqualTo(-180);
        assertThat(displayMatrixRotation(ROTATED_45_MP4)).isEqualTo(45);
        JsonNode tagged = probe(ROTATE_TAG_90_MKV).get("streams").get(0);
        assertThat(tagged.has("side_data_list")).isFalse();
        assertThat(tagged.get("tags").get("ROTATE").asString()).isEqualTo("90");
    }

    @Test
    void webmVideoStreamHasNoDurationSoTheFormatDurationApplies() {
        // ADR-010 D26: the media duration falls back to the format duration when the stream has none.
        assertThat(probe(VP9_OPUS_WEBM).get("streams").get(0).has("duration")).isFalse();
    }

    @Test
    void mismatchedTextAndTruncatedContentFailUnderTheTrustedDemuxer() throws IOException {
        for (MediaFixture fixture : List.of(EBML_UNDER_MP4_KEY, TEXT_UNDER_MP4_KEY, HLS_PLAYLIST_UNDER_MP4_KEY,
                FFCONCAT_UNDER_MKV_KEY, TRUNCATED_MOOV_AT_END_MP4)) {
            MediaTools.Result result = tools.probe(fixtures.path(fixture), fixture.demuxer(), List.of());
            assertThat(result.exitCode()).as(fixture.name()).isNotZero();
        }
        assertThat(HexFormat.of().formatHex(Files.readAllBytes(fixtures.path(EBML_UNDER_MP4_KEY)), 0, 4)).isEqualTo("1a45dfa3");
        assertThat(Files.readString(fixtures.path(HLS_PLAYLIST_UNDER_MP4_KEY))).startsWith("#EXTM3U").contains("file:" + fixtures.canary());
        assertThat(Files.readString(fixtures.path(FFCONCAT_UNDER_MKV_KEY))).startsWith("ffconcat version 1.0");
        assertThat(fixtures.canary()).doesNotExist();
    }

    @Test
    void truncatedFaststartStillProbesWithTheFullDuration() throws IOException {
        // The moov box is intact, so only the encode and the output validation can reject it (ADR-010 D27).
        JsonNode format = probe(TRUNCATED_FASTSTART_MP4).get("format");

        assertThat(Double.parseDouble(format.get("duration").asString())).isCloseTo(MediaFixtures.SOURCE_SECONDS, within(0.1));
        assertThat(Files.size(fixtures.path(TRUNCATED_FASTSTART_MP4)))
                .isLessThan(Files.size(fixtures.path(H264_AAC_MP4)) / 3 + 1);
    }

    @Test
    void wellFormedSourcesTheWorkerMustRejectProbeAsDeclared() {
        assertStreams(AUDIO_ONLY_MP4, "audio aac 2ch");
        JsonNode coverArt = probe(COVER_ART_ONLY_MP4);
        assertThat(summaries(coverArt)).containsExactly("audio aac 2ch", "video png - rgb24 320x320");
        assertThat(coverArt.get("streams").get(1).get("disposition").get("attached_pic").asInt()).isEqualTo(1);
        JsonNode videoWithCoverArt = probe(VIDEO_WITH_COVER_ART_MP4);
        assertThat(summaries(videoWithCoverArt)).containsExactly("video h264 Constrained Baseline yuv420p 320x180",
                "audio aac 1ch", "video png - rgb24 320x320");
        assertThat(videoWithCoverArt.get("streams").get(2).get("disposition").get("attached_pic").asInt()).isEqualTo(1);
        assertThat(summaries(probe(MPEG2_VIDEO_MKV)).getFirst()).startsWith("video mpeg2video");
        assertStreams(MP2_AUDIO_MKV, "video h264 Constrained Baseline yuv420p 320x180", "audio mp2 1ch");
        assertThat(probe(NINE_STREAMS_MKV).get("streams")).hasSize(9);
        assertThat(probe(NINE_STREAMS_MP4).get("streams")).hasSize(9);
        assertThat(summaries(probe(OVER_CAP_2560X1440_MP4))).containsExactly("video h264 Constrained Baseline yuv420p 2560x1440");
    }

    @Test
    void maxStreamsFailsMovButSilentlyDropsMatroskaTracks() {
        // FFmpeg 7.1 behaviour: with -max_streams 8 a nine-track Matroska file probes successfully with eight
        // streams, so a stream-count check alone cannot see the ninth track. Issue 5.4 treats the demuxer's
        // max_streams message as the cap violation (FfprobeServiceFfmpegTests in the probe module).
        MediaTools.Result matroska = tools.probe(fixtures.path(NINE_STREAMS_MKV), NINE_STREAMS_MKV.demuxer(),
                List.of("-max_streams", "8"));
        MediaTools.Result mov = tools.probe(fixtures.path(NINE_STREAMS_MP4), NINE_STREAMS_MP4.demuxer(),
                List.of("-max_streams", "8"));

        assertThat(matroska.exitCode()).isZero();
        assertThat(JsonMapper.builder().build().readTree(matroska.stdout()).get("streams")).hasSize(8);
        assertThat(matroska.stderr()).contains("max_streams");
        assertThat(mov.exitCode()).isNotZero();
    }

    private static JsonNode probe(MediaFixture fixture) {
        return tools.probeJson(fixtures.path(fixture), fixture.demuxer());
    }

    private static int displayMatrixRotation(MediaFixture fixture) {
        JsonNode sideData = probe(fixture).get("streams").get(0).get("side_data_list").get(0);
        assertThat(sideData.get("side_data_type").asString()).as(fixture.name()).isEqualTo("Display Matrix");
        return sideData.get("rotation").asInt();
    }

    private static void assertStreams(MediaFixture fixture, String... expected) {
        assertThat(summaries(probe(fixture))).as(fixture.name()).containsExactly(expected);
    }

    private static List<String> summaries(JsonNode probe) {
        List<String> summaries = new ArrayList<>();
        for (JsonNode stream : probe.get("streams")) {
            String type = stream.get("codec_type").asString();
            String codec = stream.get("codec_name").asString();
            summaries.add(switch (type) {
                case "video" -> String.join(" ", type, codec, text(stream, "profile"), text(stream, "pix_fmt"),
                        stream.get("width").asInt() + "x" + stream.get("height").asInt());
                case "audio" -> type + " " + codec + " " + stream.get("channels").asInt() + "ch";
                default -> type + " " + codec;
            });
        }
        return summaries;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null ? "-" : value.asString();
    }
}
