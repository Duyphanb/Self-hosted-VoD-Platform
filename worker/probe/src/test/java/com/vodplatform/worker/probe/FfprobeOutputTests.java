package com.vodplatform.worker.probe;

import static com.vodplatform.worker.probe.WorkerFailureCode.MEDIA_LIMIT_EXCEEDED;
import static com.vodplatform.worker.probe.WorkerFailureCode.PROBE_FAILED;
import static com.vodplatform.worker.probe.WorkerFailureCode.UNSUPPORTED_MEDIA;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The ADR-010 D26 parsing rules over FFprobe JSON. */
class FfprobeOutputTests {
    /** The ADR-010 D26 caps; the timeout is only a test value. */
    private static final ProbeSettings POLICY = new ProbeSettings("ffprobe", 30, 10_800, 1920, 1080, 8);
    private static final String NOT_ATTACHED = "\"disposition\":{\"attached_pic\":0}";
    private static final String ATTACHED = "\"disposition\":{\"attached_pic\":1}";
    /** The D25 binary's output for a 2 s 320x180 H.264/AAC MP4 with a 90-degree Display Matrix. */
    private static final String ROTATED_MP4 = """
            {
                "programs": [

                ],
                "stream_groups": [

                ],
                "streams": [
                    {
                        "index": 0,
                        "codec_name": "h264",
                        "codec_type": "video",
                        "width": 320,
                        "height": 180,
                        "duration": "2.000000",
                        "disposition": {
                            "attached_pic": 0
                        },
                        "tags": {

                        },
                        "side_data_list": [
                            {
                                "side_data_type": "Display Matrix",
                                "rotation": 90
                            }
                        ]
                    },
                    {
                        "index": 1,
                        "codec_name": "aac",
                        "codec_type": "audio",
                        "duration": "2.000000",
                        "disposition": {
                            "attached_pic": 0
                        },
                        "tags": {

                        }
                    }
                ],
                "format": {
                    "duration": "2.000000",
                    "bit_rate": "383788"
                }
            }
            """;

    @Test
    void readsTheD25OutputOfARotatedFile() {
        MediaMetadata metadata = metadata(ROTATED_MP4);

        assertThat(metadata).isEqualTo(new MediaMetadata(Duration.ofSeconds(2), 180, 320, 90, false, "h264", 383_788));
        assertThat(metadata.durationSeconds()).isEqualTo(2);
    }

    @Test
    void theSelectedStreamDurationIsTheMediaDurationRoundedUpForStorage() {
        MediaMetadata metadata = metadata(probe(format("13.100000"), video(640, 360, "\"duration\":\"12.500000\""), audio()));

        assertThat(metadata.duration()).isEqualTo(Duration.ofMillis(12_500));
        assertThat(metadata.durationSeconds()).isEqualTo(13);
    }

    @Test
    void theFormatDurationAppliesWhenTheStreamHasNone() {
        // WebM and Matroska video streams carry no duration: FFprobe omits it, or reports N/A.
        assertThat(metadata(probe(format("13.004000"), video(640, 360), audio())).duration()).isEqualTo(Duration.ofMillis(13_004));
        assertThat(metadata(probe(format("13.004000"), video(640, 360, "\"duration\":\"N/A\""))).durationSeconds()).isEqualTo(14);
        assertThat(metadata(probe(format("13.004000"), video(640, 360, "\"duration\":null"))).durationSeconds()).isEqualTo(14);
    }

    @Test
    void durationIsKeptToTheNanosecondAndNeverRoundedDown() {
        assertThat(metadata(probe(format("1.0000000001"), video(64, 64))).duration()).isEqualTo(Duration.ofSeconds(1, 1));
        assertThat(metadata(probe(format("1.0000000001"), video(64, 64))).durationSeconds()).isEqualTo(2);
        assertThat(metadata(probe("\"duration\":2.25", video(64, 64))).duration()).isEqualTo(Duration.ofMillis(2250));
    }

    @Test
    void selectsTheFirstVideoStreamThatIsNotAnAttachedPicture() {
        String coverArt = stream("video", "h264", "\"width\":320", "\"height\":320", ATTACHED);
        String first = stream("video", "hevc", "\"width\":1280", "\"height\":720", NOT_ATTACHED);
        String second = stream("video", "vp9", "\"width\":64", "\"height\":64", NOT_ATTACHED);

        MediaMetadata metadata = metadata(probe(format("10.0"), audio(), coverArt, first, second));

        assertThat(List.of(metadata.codec(), metadata.width(), metadata.height())).containsExactly("hevc", 1280, 720);
    }

    @Test
    void mediaWithoutASelectableVideoStreamIsUnsupported() {
        assertFails(probe(format("10.0"), audio()), UNSUPPORTED_MEDIA);
        assertFails(probe(format("10.0"), audio(), stream("video", "h264", "\"width\":320", "\"height\":320", ATTACHED)),
                UNSUPPORTED_MEDIA);
        assertFails(probe(format("10.0")), UNSUPPORTED_MEDIA);
        // A stream without disposition.attached_pic=0 is not selectable.
        assertFails(probe(format("10.0"), stream("video", "h264", "\"width\":64", "\"height\":64")), UNSUPPORTED_MEDIA);
    }

    @ParameterizedTest
    @CsvSource({"0, 0, 640, 360", "90, 90, 360, 640", "-90, 270, 360, 640", "180, 180, 640, 360", "-180, 180, 640, 360",
            "270, 270, 360, 640", "-270, 90, 360, 640", "360, 0, 640, 360", "89.6, 90, 360, 640", "-90.4, 270, 360, 640",
            "'\"-90\"', 270, 360, 640"})
    void displayMatrixRotationIsRoundedNormalizedAndSwapsQuarterTurns(String reported, int rotation, int width, int height) {
        MediaMetadata metadata = metadata(probe(format("5.0"), video(640, 360, displayMatrix(reported))));

        assertThat(List.of(metadata.rotation(), metadata.width(), metadata.height())).containsExactly(rotation, width, height);
        assertThat(metadata.rotationFromLegacyTag()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"45", "1", "-135", "91", "179.4"})
    void rotationsOtherThanRightAnglesAreUnsupported(String reported) {
        assertFails(probe(format("5.0"), video(640, 360, displayMatrix(reported))), UNSUPPORTED_MEDIA);
    }

    @Test
    void theRotateTagIsTheFallbackInAnyCase() {
        // The legacy tag counts clockwise, the opposite of the Display Matrix: rotate=90 is a matrix of -90, so 270.
        assertThat(metadata(probe(format("5.0"), video(640, 360, "\"tags\":{\"ROTATE\":\"90\"}"))).rotation()).isEqualTo(270);
        MediaMetadata counterclockwise = metadata(probe(format("5.0"), video(640, 360, "\"tags\":{\"rotate\":\"-90\"}")));
        assertThat(List.of(counterclockwise.rotation(), counterclockwise.width())).containsExactly(90, 360);
        assertThat(counterclockwise.rotationFromLegacyTag()).isTrue();
        assertThat(metadata(probe(format("5.0"), video(640, 360, "\"tags\":{\"rotate\":\"180\"}"))).rotation()).isEqualTo(180);
        assertThat(metadata(probe(format("5.0"), video(640, 360, "\"tags\":{\"rotate\":\"270\"}"))).rotation()).isEqualTo(90);
        assertFails(probe(format("5.0"), video(640, 360, "\"tags\":{\"rotate\":\"45\"}")), UNSUPPORTED_MEDIA);
        // The Display Matrix wins over the tag; other side data carries no rotation.
        MediaMetadata matrixWins = metadata(probe(format("5.0"),
                video(640, 360, displayMatrix("0"), "\"tags\":{\"rotate\":\"90\"}")));
        assertThat(matrixWins.rotation()).isZero();
        assertThat(matrixWins.rotationFromLegacyTag()).isFalse();
        assertThat(metadata(probe(format("5.0"), video(640, 360, "\"side_data_list\":[{\"side_data_type\":\"Mastering display "
                + "metadata\"},{\"side_data_type\":\"Display Matrix\",\"rotation\":-90}]"))).rotation()).isEqualTo(270);
        assertThat(metadata(probe(format("5.0"), video(640, 360,
                "\"side_data_list\":[{\"side_data_type\":\"Content light level metadata\"}]"))).rotation()).isZero();
    }

    @Test
    void unreadableRotationFailsTheProbe() {
        assertFails(probe(format("5.0"), video(640, 360, "\"tags\":{\"rotate\":\"ninety\"}")), PROBE_FAILED);
        assertFails(probe(format("5.0"), video(640, 360, displayMatrix("\"N/A\""))), PROBE_FAILED);
        assertFails(probe(format("5.0"), video(640, 360, displayMatrix("true"))), PROBE_FAILED);
        assertFails(probe(format("5.0"), video(640, 360, "\"side_data_list\":[{\"side_data_type\":\"Display Matrix\"}]",
                "\"tags\":{\"rotate\":\"90\"}")), PROBE_FAILED);
    }

    @ParameterizedTest
    @CsvSource({"1920, 1080, 0", "1080, 1920, 0", "1920, 1080, 90", "1080, 1920, -90", "1280, 720, 180", "64, 64, 0"})
    void dimensionsWithinTheCapsPassInEitherOrientation(int width, int height, int rotation) {
        assertThat(metadata(probe(format("60.0"), video(width, height, displayMatrix(String.valueOf(rotation))))).codec())
                .isEqualTo("h264");
    }

    @ParameterizedTest
    @CsvSource({"1921, 1080", "1080, 1921", "1920, 1081", "1081, 1920", "2560, 1440", "1440, 2560", "1920, 1200"})
    void dimensionsOverTheLongOrShortSideCapExceedTheLimit(int width, int height) {
        assertFails(probe(format("60.0"), video(width, height)), MEDIA_LIMIT_EXCEEDED);
        assertFails(probe(format("60.0"), video(width, height, displayMatrix("90"))), MEDIA_LIMIT_EXCEEDED);
    }

    @Test
    void theDurationCapHoldsOnWholeSeconds() {
        assertThat(metadata(probe(format("10800.000000"), video(64, 64))).durationSeconds()).isEqualTo(10_800);
        assertFails(probe(format("10800.000001"), video(64, 64)), MEDIA_LIMIT_EXCEEDED);
        assertFails(probe(format("999999999999.0"), video(64, 64)), MEDIA_LIMIT_EXCEEDED);
    }

    @Test
    void moreStreamsThanTheCapExceedTheLimit() {
        List<String> streams = new ArrayList<>(List.of(video(64, 64)));
        streams.addAll(Collections.nCopies(7, audio()));
        assertThat(metadata(probe(format("5.0"), streams.toArray(String[]::new))).codec()).isEqualTo("h264");

        streams.add(stream("data", "bin_data"));
        assertFails(probe(format("5.0"), streams.toArray(String[]::new)), MEDIA_LIMIT_EXCEEDED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"width\":0,\"height\":360", "\"height\":360", "\"width\":\"640\",\"height\":360",
            "\"width\":640.5,\"height\":360", "\"width\":-640,\"height\":360", "\"width\":3000000000,\"height\":360"})
    void missingOrInvalidDimensionsFailTheProbe(String dimensions) {
        assertFails(probe(format("5.0"), stream("video", "h264", dimensions, NOT_ATTACHED)), PROBE_FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.000000", "-1.000000", "NaN", "Infinity", "1e3", "0x10", "", "13,5", " 13.0", "N/A"})
    void invalidDurationsFailTheProbe(String duration) {
        assertFails(probe(format(duration), video(640, 360)), PROBE_FAILED);
    }

    @Test
    void aMissingDurationFailsTheProbe() {
        assertFails(probe("\"bit_rate\":\"1000\"", video(640, 360)), PROBE_FAILED);
        assertFails("{\"streams\":[" + video(640, 360) + "]}", PROBE_FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "\"text\"", "{", "{\"streams\":[]} {}", "{\"streams\":{}}", "{\"format\":{}}",
            "{\"streams\":[1]}", "{\"streams\":[],\"streams\":[]}", "\u0000"})
    void malformedOutputFailsTheProbe(String json) {
        assertFails(json, PROBE_FAILED);
    }

    @Test
    void deeplyNestedOutputFailsWithinTheParserBound() {
        assertFails("{\"streams\":[],\"x\":" + "[".repeat(64) + "]".repeat(64) + "}", PROBE_FAILED);
    }

    @Test
    void unknownKeysAreIgnored() {
        String json = probe(format("5.0") + ",\"probe_score\":100,\"future\":{\"x\":[1,2]}",
                video(640, 360, "\"profile\":\"High\"", "\"new_field\":{\"nested\":true}"), audio());

        assertThat(metadata(json).width()).isEqualTo(640);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {"'\"383788\"', 383788", "'\"2147483647\"', 2147483647",
            "'\"2147483648\"', null", "'\"99999999999999999999999999\"', null", "'\"0\"', null", "'\"-5\"', null",
            "'\"N/A\"', null", "'\"12.5\"', null", "5000, 5000", "null, null"})
    void bitrateIsTheFormatBitrateOrNullWhenMissingOrOutsideTheIntRange(String reported, Integer expected) {
        String format = "\"duration\":\"5.0\"" + (reported == null ? "" : ",\"bit_rate\":" + reported);

        assertThat(metadata(probe(format, video(640, 360))).bitrate()).isEqualTo(expected);
    }

    @Test
    void decodedStreamsOutsideTheD26FamiliesAreUnsupported() {
        assertFails(probe(format("5.0"), video(640, 360), stream("audio", "mp2", NOT_ATTACHED)), UNSUPPORTED_MEDIA);
        assertFails(probe(format("5.0"), video(640, 360), stream("subtitle", "hdmv_pgs_subtitle")), UNSUPPORTED_MEDIA);
        assertFails(probe(format("5.0"), video(640, 360), "{\"codec_type\":\"audio\"}"), UNSUPPORTED_MEDIA);
        assertFails(probe(format("5.0"), stream("video", "mpeg2video", "\"width\":640", "\"height\":360", NOT_ATTACHED)),
                UNSUPPORTED_MEDIA);
        assertFails(probe(format("5.0"), video(640, 360), stream("video", "png", "\"width\":320", "\"height\":320", ATTACHED)),
                UNSUPPORTED_MEDIA);
    }

    @Test
    void allowedFamiliesAndStreamsNoToolDecodesAreAccepted() {
        String subtitles = stream("subtitle", "subrip");
        String webvtt = stream("subtitle", "webvtt");
        String opus = stream("audio", "opus");
        String data = stream("data", "bin_data");
        String font = stream("attachment", "ttf");
        String untyped = "{\"index\":7}";

        MediaMetadata metadata = metadata(probe(format("5.0"), data, stream("video", "vp9", "\"width\":640", "\"height\":360",
                NOT_ATTACHED), opus, subtitles, webvtt, font, untyped));

        assertThat(metadata.codec()).isEqualTo("vp9");
    }

    private static MediaMetadata metadata(String json) {
        return FfprobeOutput.metadata(json.getBytes(UTF_8), POLICY);
    }

    private static void assertFails(String json, WorkerFailureCode code) {
        assertThatThrownBy(() -> metadata(json)).as(json).isInstanceOfSatisfying(WorkerFailureException.class,
                failure -> assertThat(failure.code()).isEqualTo(code));
    }

    private static String probe(String format, String... streams) {
        return "{\"programs\":[],\"streams\":[" + String.join(",", streams) + "],\"format\":{" + format + "}}";
    }

    private static String format(String duration) {
        return "\"duration\":\"" + duration + "\",\"bit_rate\":\"500000\"";
    }

    private static String video(int width, int height, String... fields) {
        List<String> all = new ArrayList<>(List.of("\"width\":" + width, "\"height\":" + height, NOT_ATTACHED));
        all.addAll(List.of(fields));
        return stream("video", "h264", all.toArray(String[]::new));
    }

    private static String audio() {
        return stream("audio", "aac", NOT_ATTACHED);
    }

    private static String displayMatrix(String rotation) {
        return "\"side_data_list\":[{\"side_data_type\":\"Display Matrix\",\"rotation\":" + rotation + "}]";
    }

    private static String stream(String type, String codec, String... fields) {
        StringBuilder json = new StringBuilder("{\"codec_type\":\"" + type + "\",\"codec_name\":\"" + codec + "\"");
        for (String field : fields) json.append(',').append(field);
        return json.append('}').toString();
    }
}
