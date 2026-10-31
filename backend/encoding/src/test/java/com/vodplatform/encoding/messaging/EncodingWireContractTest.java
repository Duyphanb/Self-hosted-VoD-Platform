package com.vodplatform.encoding.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import tools.jackson.databind.json.JsonMapper;

class EncodingWireContractTest {
    private static final UUID JOB = UUID.fromString("6f1d2a4e-8c3b-4a5f-9e7d-1b2c3d4e5f60");
    private static final UUID ASSET = UUID.fromString("0b6c1f7e-2a3d-4e5f-8a9b-0c1d2e3f4a5b");
    private final EncodingJobMessageCodec codec = new EncodingJobMessageCodec();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void goldenFixtureAndEncodingEqualTheFrozenAdrExample() throws Exception {
        Path root = root();
        byte[] fixture = Files.readAllBytes(root.resolve("docs/testing/fixtures/encoding-job-v1.json"));
        EncodingJobMessage message = new EncodingJobMessage(JOB, ASSET, 1);
        assertThat(codec.decode(fixture, JOB.toString())).isEqualTo(message);
        assertThat(json.readTree(codec.encode(message))).isEqualTo(json.readTree(fixture));
        String adr = Files.readString(root.resolve("docs/architecture/adr/ADR-010.md"));
        // readString preserves platform newlines: normalize before locating the frozen block.
        adr = adr.replace("\r\n", "\n");
        int start = adr.indexOf("```json\n");
        assertThat(start).isGreaterThanOrEqualTo(0);
        String example = adr.substring(start + 8, adr.indexOf("\n```", start + 8));
        assertThat(json.readTree(fixture)).isEqualTo(json.readTree(example));
    }

    @Test
    void duplicatedWireClassesStayIdenticalAcrossReactors() throws Exception {
        Path root = root();
        for (String name : List.of("EncodingJobMessage", "EncodingJobMessageCodec",
                "InvalidEncodingJobMessageException", "EncodingQueueTopology")) {
            String backend = Files.readString(root.resolve(
                    "backend/encoding/src/main/java/com/vodplatform/encoding/messaging/" + name + ".java"));
            String worker = Files.readString(root.resolve(
                    "worker/queue/src/main/java/com/vodplatform/worker/queue/messaging/" + name + ".java"));
            assertThat(worker.replace("com.vodplatform.worker.queue", "com.vodplatform.encoding").replace("\r\n", "\n"))
                    .as(name).isEqualTo(backend.replace("\r\n", "\n"));
        }
    }

    @Test
    void unknownFieldsAndCanonicalUppercaseUuidAreAccepted() {
        String body = body().replace("\"attempt\":1", "\"attempt\":1,\"future\":{\"flag\":true}")
                .replace(JOB.toString(), JOB.toString().toUpperCase());
        assertThat(codec.decode(bytes(body), JOB.toString())).isEqualTo(new EncodingJobMessage(JOB, ASSET, 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.0", "\"1\"", "true", "null", "2147483648"})
    void invalidAttemptsAreRejected(String attempt) {
        assertMalformed(body().replace("\"attempt\":1", "\"attempt\":" + attempt));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.0", "\"1\"", "true", "null"})
    void versionMustBeAnIntegralNumber(String version) {
        assertMalformed(body().replace("\"schemaVersion\":1", "\"schemaVersion\":" + version));
    }

    @Test
    void rejectsUnsupportedVersionMissingFieldsDuplicatesTrailingTokensAndNonCanonicalIds() {
        assertThatIllegalArgumentException().isThrownBy(() -> codec.decode(
                bytes(body().replace("\"schemaVersion\":1", "\"schemaVersion\":2")), JOB.toString()))
                .withMessage("Invalid encoding job message: UNSUPPORTED_VERSION");
        for (String invalid : List.of("[]", "null", "{}", "", body() + " {}",
                body().replace("\"attempt\":1", "\"attempt\":1,\"attempt\":2"),
                body().replace(JOB.toString(), "1-1-1-1-1"),
                body().replace(ASSET.toString(), "not-a-uuid"),
                body().replace("\"schemaVersion\":1,", ""),
                body().replace("\"attempt\":1", "\"attempt\":1,\"future\":0,\"future\":1"))) {
            assertMalformed(invalid);
        }
        assertThatIllegalArgumentException().isThrownBy(() -> codec.decode(
                new byte[EncodingJobMessageCodec.MAX_BODY_BYTES + 1], JOB.toString()))
                .withMessage("Invalid encoding job message: MALFORMED");
    }

    @Test
    void rejectsMissingOrMismatchedMessageIdWithoutEchoingInput() {
        for (String id : new String[]{null, UUID.randomUUID().toString(), "secret-not-an-id"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> codec.decode(bytes(body()), id))
                    .withMessage("Invalid encoding job message: MESSAGE_ID_MISMATCH");
        }
        assertThatIllegalArgumentException().isThrownBy(() -> new EncodingJobMessage(JOB, ASSET, 0));
    }

    @Test
    void topologyIsTheExactClassicAdrSpecification() {
        String name = "video-encoding-queue";
        var declarations = EncodingQueueTopology.declarables(name);
        assertThat(declarations.getDeclarables()).hasSize(4);
        List<Queue> queues = declarations.getDeclarablesByType(Queue.class);
        assertThat(queues).hasSize(2);
        Queue source = queues.stream().filter(q -> q.getName().equals(name)).findFirst().orElseThrow();
        Queue dlq = queues.stream().filter(q -> q.getName().equals(name + ".dlq")).findFirst().orElseThrow();
        for (Queue queue : queues) {
            assertThat(queue.isDurable()).isTrue();
            assertThat(queue.isExclusive()).isFalse();
            assertThat(queue.isAutoDelete()).isFalse();
        }
        assertThat(source.getArguments()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "x-dead-letter-exchange", name + ".dlx", "x-dead-letter-routing-key", name + ".dlq"));
        assertThat(dlq.getArguments()).isEmpty();
        DirectExchange dlx = declarations.getDeclarablesByType(DirectExchange.class).getFirst();
        assertThat(dlx.getName()).isEqualTo(name + ".dlx");
        assertThat(dlx.isDurable()).isTrue();
        assertThat(dlx.isAutoDelete()).isFalse();
        assertThat(dlx.isInternal()).isFalse();
        assertThat(dlx.getArguments()).isEmpty();
        Binding binding = declarations.getDeclarablesByType(Binding.class).getFirst();
        assertThat(binding.getExchange()).isEqualTo(name + ".dlx");
        assertThat(binding.getDestination()).isEqualTo(name + ".dlq");
        assertThat(binding.getRoutingKey()).isEqualTo(name + ".dlq");
        assertThat(binding.getArguments()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "queue ", "amq.reserved", "bad/queue", "bad\nqueue"})
    void rejectsUnsafeQueueNames(String name) {
        assertThatIllegalArgumentException().isThrownBy(() -> EncodingQueueTopology.declarables(name));
    }

    @Test
    void queueSuffixesFitTheBrokerNameLimit() {
        assertThat(EncodingQueueTopology.declarables("a".repeat(251)).getDeclarables()).hasSize(4);
        assertThatIllegalArgumentException().isThrownBy(() -> EncodingQueueTopology.declarables("a".repeat(252)));
    }

    private void assertMalformed(String text) {
        assertThatIllegalArgumentException().isThrownBy(() -> codec.decode(bytes(text), JOB.toString()))
                .withMessage("Invalid encoding job message: MALFORMED");
    }

    private String body() { return new String(codec.encode(new EncodingJobMessage(JOB, ASSET, 1)), StandardCharsets.UTF_8); }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static Path root() {
        for (Path path = Path.of("").toAbsolutePath(); path != null; path = path.getParent()) {
            if (Files.isRegularFile(path.resolve("docs/testing/fixtures/encoding-job-v1.json"))) return path;
        }
        throw new AssertionError("Shared encoding-job-v1.json fixture is missing");
    }
}
