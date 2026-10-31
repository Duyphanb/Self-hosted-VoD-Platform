package com.vodplatform.encoding.messaging;

import static com.vodplatform.encoding.messaging.InvalidEncodingJobMessageException.Reason.MALFORMED;
import static com.vodplatform.encoding.messaging.InvalidEncodingJobMessageException.Reason.MESSAGE_ID_MISMATCH;
import static com.vodplatform.encoding.messaging.InvalidEncodingJobMessageException.Reason.UNSUPPORTED_VERSION;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Dedicated strict wire codec; does not use the HTTP mapper or Java type-mapping converters. */
public final class EncodingJobMessageCodec {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_BODY_BYTES = 65_536;
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public byte[] encode(EncodingJobMessage message) {
        Objects.requireNonNull(message, "message");
        return mapper.writeValueAsBytes(mapper.createObjectNode()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("encodingJobId", message.encodingJobId().toString())
                .put("videoAssetId", message.videoAssetId().toString())
                .put("attempt", message.attempt()));
    }

    public EncodingJobMessage decode(byte[] body, String messageId) {
        if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES) throw invalid(MALFORMED);
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (RuntimeException ignored) {
            throw invalid(MALFORMED);
        }
        if (root == null || !root.isObject()) throw invalid(MALFORMED);
        JsonNode version = root.get("schemaVersion");
        if (version == null || !version.isIntegralNumber()) throw invalid(MALFORMED);
        if (!version.canConvertToInt() || version.intValue() != SCHEMA_VERSION) throw invalid(UNSUPPORTED_VERSION);
        UUID job = uuid(root.get("encodingJobId"));
        UUID asset = uuid(root.get("videoAssetId"));
        JsonNode attempt = root.get("attempt");
        if (attempt == null || !attempt.isIntegralNumber() || !attempt.canConvertToInt() || attempt.intValue() < 1) {
            throw invalid(MALFORMED);
        }
        if (messageId == null || !UUID_TEXT.matcher(messageId).matches()
                || !job.equals(UUID.fromString(messageId))) throw invalid(MESSAGE_ID_MISMATCH);
        return new EncodingJobMessage(job, asset, attempt.intValue());
    }

    private static UUID uuid(JsonNode value) {
        if (value == null || !value.isString() || !UUID_TEXT.matcher(value.asString()).matches()) {
            throw invalid(MALFORMED);
        }
        return UUID.fromString(value.asString());
    }

    private static InvalidEncodingJobMessageException invalid(InvalidEncodingJobMessageException.Reason reason) {
        return new InvalidEncodingJobMessageException(reason);
    }
}
