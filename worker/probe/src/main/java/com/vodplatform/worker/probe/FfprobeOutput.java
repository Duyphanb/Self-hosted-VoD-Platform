package com.vodplatform.worker.probe;

import static com.vodplatform.worker.probe.WorkerFailureCode.MEDIA_LIMIT_EXCEEDED;
import static com.vodplatform.worker.probe.WorkerFailureCode.PROBE_FAILED;
import static com.vodplatform.worker.probe.WorkerFailureCode.UNSUPPORTED_MEDIA;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns FFprobe's JSON into {@link MediaMetadata} under the ADR-010 D26 rules. The JSON is read as a bounded
 * tree, never bound to classes; unknown keys are ignored, and any value the rules need that is missing,
 * mistyped or out of range fails closed with a D28 code.
 */
final class FfprobeOutput {
    private static final BigInteger MAX_INT = BigInteger.valueOf(Integer.MAX_VALUE);
    private static final Pattern DECIMAL = Pattern.compile("[0-9]{1,12}(\\.[0-9]{1,12})?");
    private static final Pattern SIGNED_DECIMAL = Pattern.compile("[-+]?[0-9]{1,12}(\\.[0-9]{1,12})?");
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,30}");
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).build()).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private FfprobeOutput() {
    }

    static MediaMetadata metadata(byte[] json, ProbeSettings settings) {
        JsonNode root = parse(json);
        JsonNode streams = root.path("streams");
        if (!streams.isArray()) throw failure(PROBE_FAILED, "FFprobe output has no stream list");
        if (streams.size() > settings.maxStreams()) {
            throw failure(MEDIA_LIMIT_EXCEEDED, "Media has more streams than WORKER_MAX_STREAMS");
        }
        JsonNode selected = null;
        for (JsonNode stream : streams) {
            if (!stream.isObject()) throw failure(PROBE_FAILED, "FFprobe output has a malformed stream");
            String type = text(stream.path("codec_type"));
            // Data and attachment streams have no decoder, so neither tool decodes them; Issue 5.5 maps only
            // 0:V:0 and 0:a:0?. A decoded stream must belong to a D26 family, or no listed decoder can open it.
            if (AllowedDecoder.MediaType.isDecoded(type) && !AllowedDecoder.allows(type, text(stream.path("codec_name")))) {
                throw failure(UNSUPPORTED_MEDIA, "Media has a stream outside the decoder allowlist");
            }
            // D26 and FFmpeg's 0:V:0: the first video stream that is not an attached picture (cover art).
            if (selected == null && "video".equals(type) && isZero(stream.path("disposition").path("attached_pic"))) {
                selected = stream;
            }
        }
        if (selected == null) throw failure(UNSUPPORTED_MEDIA, "Media has no video stream other than cover art");
        int codedWidth = positiveInt(selected.path("width"), "width");
        int codedHeight = positiveInt(selected.path("height"), "height");
        int rotation = rotation(selected);
        boolean quarterTurn = rotation == 90 || rotation == 270;
        int width = quarterTurn ? codedHeight : codedWidth;
        int height = quarterTurn ? codedWidth : codedHeight;
        BigDecimal seconds = durationSeconds(selected, root.path("format"));
        // Orientation-neutral caps: portrait 1080x1920 passes the 1920 long-side and 1080 short-side caps.
        if (Math.max(width, height) > settings.maxWidth() || Math.min(width, height) > settings.maxHeight()) {
            throw failure(MEDIA_LIMIT_EXCEEDED, "Media dimensions exceed WORKER_MAX_WIDTH or WORKER_MAX_HEIGHT");
        }
        // A whole-second cap holds exactly when the duration rounded up to whole seconds does.
        if (seconds.compareTo(BigDecimal.valueOf(settings.maxDurationSeconds())) > 0) {
            throw failure(MEDIA_LIMIT_EXCEEDED, "Media duration exceeds WORKER_MAX_DURATION_SECONDS");
        }
        return new MediaMetadata(toDuration(seconds), width, height, rotation, text(selected.path("codec_name")),
                bitrate(root.path("format").path("bit_rate")));
    }

    private static JsonNode parse(byte[] json) {
        try {
            JsonNode root = JSON.readTree(json);
            if (root != null && root.isObject()) return root;
        } catch (JacksonException e) {
            // Reported below without the parser's message, which can quote the input.
        }
        throw failure(PROBE_FAILED, "FFprobe output is not a JSON object");
    }

    /**
     * The Display Matrix rotation, or the legacy {@code rotate} tag (any case; the Matroska muxer writes
     * {@code ROTATE}) when there is none. FFprobe reports the matrix angle counterclockwise in [-180, 180]: it
     * is rounded to whole degrees and normalized modulo 360, so -90 becomes 270 and -180 becomes 180.
     *
     * <p>The legacy tag counts clockwise: FFmpeg before 5.0 exported a Display Matrix of -90 degrees as
     * {@code rotate=90} and rotated such a file clockwise for display. The tag is therefore negated into the
     * counterclockwise matrix convention, so {@code rotate=90} is 270. Issue 5.5 hands this one rotation to
     * FFmpeg, whose 7.1 autorotation no longer reads the tag, so the sign decides the output orientation.
     */
    private static int rotation(JsonNode stream) {
        JsonNode sideDataList = stream.path("side_data_list");
        if (sideDataList.isArray()) {
            for (JsonNode sideData : sideDataList) {
                if ("Display Matrix".equals(text(sideData.path("side_data_type"))) && sideData.has("rotation")) {
                    return rightAngle(finiteNumber(sideData.get("rotation"), "rotation"));
                }
            }
        }
        JsonNode tags = stream.path("tags");
        if (tags.isObject()) {
            for (Map.Entry<String, JsonNode> tag : tags.properties()) {
                if (tag.getKey().equalsIgnoreCase("rotate")) return rightAngle(-finiteNumber(tag.getValue(), "rotation"));
            }
        }
        return 0;
    }

    /** {@code counterclockwise} rounded to whole degrees and normalized modulo 360; only right angles pass. */
    private static int rightAngle(double counterclockwise) {
        int normalized = (int) Math.floorMod(Math.round(counterclockwise), 360L);
        if (normalized % 90 != 0) throw failure(UNSUPPORTED_MEDIA, "Media rotation is not a multiple of 90 degrees");
        return normalized;
    }

    /**
     * The selected stream's duration in seconds, or the format duration when the stream has none (WebM and
     * Matroska streams report none); it must be finite and positive.
     */
    private static BigDecimal durationSeconds(JsonNode stream, JsonNode format) {
        JsonNode value = stream.path("duration");
        if (absent(value)) value = format.path("duration");
        if (absent(value)) throw failure(PROBE_FAILED, "FFprobe reported no media duration");
        BigDecimal seconds = null;
        if (value.isString() && DECIMAL.matcher(value.asString()).matches()) {
            seconds = new BigDecimal(value.asString());
        } else if (value.isNumber() && Double.isFinite(value.doubleValue())) {
            seconds = value.decimalValue();
        }
        if (seconds == null || seconds.signum() <= 0) throw failure(PROBE_FAILED, "FFprobe reported an invalid media duration");
        return seconds;
    }

    /** Exact to the nanosecond, rounding any finer remainder up so the whole-second ceiling is preserved. */
    private static Duration toDuration(BigDecimal seconds) {
        long whole = seconds.longValue();
        long nanos = seconds.subtract(BigDecimal.valueOf(whole)).movePointRight(9).setScale(0, RoundingMode.CEILING).longValue();
        return Duration.ofSeconds(whole, nanos);
    }

    private static Integer bitrate(JsonNode value) {
        BigInteger bits = null;
        if (value.isString() && DIGITS.matcher(value.asString()).matches()) {
            bits = new BigInteger(value.asString());
        } else if (value.isIntegralNumber()) {
            bits = value.bigIntegerValue();
        }
        if (bits == null || bits.signum() <= 0 || bits.compareTo(MAX_INT) > 0) return null;
        return bits.intValue();
    }

    private static double finiteNumber(JsonNode value, String field) {
        if (value.isNumber() && Double.isFinite(value.doubleValue())) return value.doubleValue();
        if (value.isString() && SIGNED_DECIMAL.matcher(value.asString()).matches()) return Double.parseDouble(value.asString());
        throw failure(PROBE_FAILED, "FFprobe reported an invalid " + field);
    }

    private static int positiveInt(JsonNode value, String field) {
        if (value.isIntegralNumber() && value.canConvertToInt() && value.intValue() > 0) return value.intValue();
        throw failure(PROBE_FAILED, "FFprobe reported an invalid " + field);
    }

    private static boolean isZero(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() == 0;
    }

    private static boolean absent(JsonNode value) {
        return value.isMissingNode() || value.isNull() || (value.isString() && "N/A".equals(value.asString()));
    }

    private static String text(JsonNode value) {
        return value.isString() ? value.asString() : null;
    }

    private static WorkerFailureException failure(WorkerFailureCode code, String message) {
        return new WorkerFailureException(code, message);
    }
}
