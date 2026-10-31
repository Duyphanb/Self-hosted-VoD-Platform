package com.vodplatform.worker.queue.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EncodingJobMessageCodecTests {
    private static final UUID JOB = UUID.fromString("6f1d2a4e-8c3b-4a5f-9e7d-1b2c3d4e5f60");
    private static final UUID ASSET = UUID.fromString("0b6c1f7e-2a3d-4e5f-8a9b-0c1d2e3f4a5b");
    private final EncodingJobMessageCodec codec = new EncodingJobMessageCodec();

    @Test
    void parsesTheSingleSharedGoldenFixtureAndEmitsOnlyTheVersionedIds() throws Exception {
        byte[] fixture = QueueFixtures.golden();
        EncodingJobMessage expected = new EncodingJobMessage(JOB, ASSET, 1);
        assertThat(codec.decode(fixture, JOB.toString())).isEqualTo(expected);
        JsonMapper mapper = JsonMapper.builder().build();
        assertThat(mapper.readTree(codec.encode(expected))).isEqualTo(mapper.readTree(fixture));
        assertThat(mapper.readTree(codec.encode(expected)).size()).isEqualTo(4);
    }

    @Test
    void ignoresUnknownFieldsIncludingNestedData() throws Exception {
        String json = goldenText().replace("\"attempt\":1", "\"attempt\":1,\"futureField\":{\"nested\":true}");
        assertThat(codec.decode(bytes(json), JOB.toString())).isEqualTo(new EncodingJobMessage(JOB, ASSET, 1));
    }

    @Test
    void rejectsMalformedBodiesWithoutRetainingUntrustedContent() throws Exception {
        String valid = goldenText();
        for (String invalid : List.of("", "null", "[]", "{}", "{", valid + " {}",
                valid.replace("\"attempt\":1", "\"attempt\":0"),
                valid.replace("\"attempt\":1", "\"attempt\":-1"),
                valid.replace("\"attempt\":1", "\"attempt\":1.0"),
                valid.replace("\"attempt\":1", "\"attempt\":\"1\""),
                valid.replace("\"attempt\":1", "\"attempt\":2147483648"),
                valid.replace("\"attempt\":1", "\"attempt\":null"),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1.0"),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
                valid.replace(JOB.toString(), "1-1-1-1-1"),
                valid.replace(ASSET.toString(), "not-a-uuid"),
                valid.replace("\"encodingJobId\":\"" + JOB + "\"", "\"encodingJobId\":null"),
                valid.replace("\"videoAssetId\":\"" + ASSET + "\"", "\"videoAssetId\":17"),
                valid.substring(0, valid.length() - 1) + ",\"attempt\":2}")) {
            assertThatThrownBy(() -> codec.decode(bytes(invalid), JOB.toString()))
                    .isInstanceOf(InvalidEncodingJobMessageException.class)
                    .hasMessage("Invalid encoding job message: MALFORMED").hasNoCause();
        }
        assertThatThrownBy(() -> codec.decode(null, JOB.toString()))
                .isInstanceOf(InvalidEncodingJobMessageException.class);
        assertThatThrownBy(() -> codec.decode(new byte[EncodingJobMessageCodec.MAX_BODY_BYTES + 1], JOB.toString()))
                .isInstanceOf(InvalidEncodingJobMessageException.class);
    }

    @Test
    void rejectsUnsupportedVersionAndMissingOrMismatchedMessageId() throws Exception {
        String valid = goldenText();
        for (String version : List.of("0", "2", "99999999999999999999")) {
            assertThatThrownBy(() -> codec.decode(bytes(valid.replace("\"schemaVersion\":1",
                            "\"schemaVersion\":" + version)), JOB.toString()))
                    .isInstanceOf(InvalidEncodingJobMessageException.class)
                    .hasMessage("Invalid encoding job message: UNSUPPORTED_VERSION");
        }
        for (String messageId : new String[] {null, "", "1-1-1-1-1", ASSET.toString()}) {
            assertThatThrownBy(() -> codec.decode(bytes(valid), messageId))
                    .isInstanceOf(InvalidEncodingJobMessageException.class)
                    .hasMessage("Invalid encoding job message: MESSAGE_ID_MISMATCH");
        }
    }

    @Test
    void constructingMessagesRejectsMissingIdsAndNonPositiveAttempts() {
        assertThatThrownBy(() -> new EncodingJobMessage(null, ASSET, 1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EncodingJobMessage(JOB, null, 1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EncodingJobMessage(JOB, ASSET, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EncodingJobMessage(JOB, ASSET, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static String goldenText() throws Exception {
        return new String(QueueFixtures.golden(), StandardCharsets.UTF_8).strip();
    }
    private static byte[] bytes(String json) { return json.getBytes(StandardCharsets.UTF_8); }
}
