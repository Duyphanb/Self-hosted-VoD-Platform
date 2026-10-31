package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.rabbitmq.client.GetResponse;
import com.vodplatform.encoding.config.EncodingQueueProperties;
import com.vodplatform.encoding.messaging.EncodingJobMessage;
import com.vodplatform.encoding.messaging.EncodingJobMessageCodec;
import com.vodplatform.encoding.messaging.EncodingJobPublisher;
import com.vodplatform.encoding.messaging.PublishOutcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** Explicit opt-in: mutates only a fresh Compose project owned by the smoke helper. */
@SpringBootTest(properties = "auth.tokens.secret=test-only-secret-with-at-least-32-bytes")
@EnabledIf("configured")
class EncodingPublisherBrokerTests {
    private static final Logger log = LoggerFactory.getLogger(EncodingPublisherBrokerTests.class);
    @Autowired EncodingJobPublisher publisher;
    @Autowired EncodingJobMessageCodec codec;
    @Autowired EncodingQueueProperties properties;
    @Autowired RabbitTemplate template;
    @Autowired RabbitAdmin admin;
    @Autowired CachingConnectionFactory connection;

    static boolean configured() {
        return "true".equals(System.getenv("ENCODING_TEST_RABBITMQ_REQUIRED"))
                || System.getenv("ENCODING_TEST_RABBITMQ_HOST") != null;
    }

    @DynamicPropertySource
    static void broker(DynamicPropertyRegistry registry) {
        assertThat(env("HOST")).isEqualTo("127.0.0.1");
        assertThat(JsonMapper.builder().build().readTree(command("inspect", "--format",
                "{{json .Config.Labels}}", env("CONTAINER"))).get("com.docker.compose.project").asString())
                .matches("vod-auth-audit-[a-z0-9][a-z0-9_-]*");
        assertThat(command("port", env("CONTAINER"), "5672/tcp").trim())
                .isEqualTo("127.0.0.1:" + env("PORT"));
        registry.add("spring.rabbitmq.host", () -> env("HOST"));
        registry.add("spring.rabbitmq.port", () -> env("PORT"));
        registry.add("spring.rabbitmq.username", () -> env("USERNAME"));
        registry.add("spring.rabbitmq.password", () -> env("PASSWORD"));
        registry.add("spring.rabbitmq.connection-timeout", () -> "2s");
        registry.add("spring.rabbitmq.channel-rpc-timeout", () -> "2s");
        registry.add("encoding.queue.publish-confirm-timeout-ms", () -> "1000");
    }

    @Test
    void productionPublisherProvesWirePersistenceOutcomesAndDeadLetterRouting() throws Exception {
        String queue = properties.name();
        admin.initialize(); // Worker already declared these exact entities; mismatch would be 406.
        admin.purgeQueue(queue);
        admin.purgeQueue(queue + ".dlq");
        EncodingJobMessage message = golden();
        assertThat(publisher.confirmTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(publisher.publish(message, Optional.of("audit-170"))).isInstanceOf(PublishOutcome.Confirmed.class);

        command("restart", env("CONTAINER"));
        connection.resetConnection();
        await(() -> {
            try { return admin.getQueueInfo(queue) != null; } catch (RuntimeException unavailable) { return false; }
        });
        GetResponse persisted = template.execute(channel -> channel.basicGet(queue, true));
        assertThat(persisted).isNotNull();
        assertThat(codec.decode(persisted.getBody(), persisted.getProps().getMessageId())).isEqualTo(message);
        assertThat(persisted.getProps().getContentType()).isEqualTo("application/json");
        assertThat(persisted.getProps().getDeliveryMode()).isEqualTo(2);
        assertThat(persisted.getProps().getHeaders().get("x-request-id").toString()).isEqualTo("audit-170");
        Set<String> headers = persisted.getProps().getHeaders().keySet();
        assertThat(headers).doesNotContain("__TypeId__", "__ContentTypeId__", "__KeyTypeId__");
        log.info("Observed encoding wire header names: {}", headers);

        admin.deleteQueue(queue);
        PublishOutcome returned = publisher.publish(message, Optional.empty());
        assertThat(returned).isInstanceOf(PublishOutcome.Returned.class);
        assertThat(((PublishOutcome.Returned) returned).replyCode()).isEqualTo(312);
        assertThat(((PublishOutcome.Returned) returned).replyText()).isEqualTo("NO_ROUTE");
        admin.initialize();

        ctl("set_policy", "--apply-to", "queues", "audit-reject", "^" + queue + "$",
                "{\"max-length\":0,\"overflow\":\"reject-publish\"}");
        try {
            await(() -> publisher.publish(message, Optional.empty()).equals(new PublishOutcome.Nacked(null)));
        } finally {
            ctl("clear_policy", "audit-reject");
        }
        admin.purgeQueue(queue);
        await(() -> publisher.publish(message, Optional.empty()) instanceof PublishOutcome.Confirmed);
        admin.purgeQueue(queue);

        ctl("set_vm_memory_high_watermark", "0");
        try {
            await(() -> ctl("eval", "rabbit_alarm:get_alarms().").contains("memory"));
            long start = System.nanoTime();
            assertThat(publisher.publish(message, Optional.empty())).isInstanceOf(PublishOutcome.TimedOut.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(6));
        } finally {
            ctl("set_vm_memory_high_watermark", "0.4");
        }
        await(() -> publisher.publish(message, Optional.empty()) instanceof PublishOutcome.Confirmed);
        admin.purgeQueue(queue);
        assertThat(publisher.publish(message, Optional.empty())).isInstanceOf(PublishOutcome.Confirmed.class);
        GetResponse withoutRequest = template.execute(channel -> {
            GetResponse received = channel.basicGet(queue, false);
            if (received != null) channel.basicReject(received.getEnvelope().getDeliveryTag(), false);
            return received;
        });
        assertThat(withoutRequest).isNotNull();
        assertThat(withoutRequest.getProps().getHeaders()).doesNotContainKey("x-request-id");
        await(() -> admin.getQueueInfo(queue + ".dlq").getMessageCount() == 1);
        GetResponse dead = template.execute(channel -> channel.basicGet(queue + ".dlq", true));
        assertThat(dead.getProps().getHeaders().get("x-death").toString()).contains("rejected", queue);
        assertThat(admin.getQueueInfo(queue + ".dlq").getConsumerCount()).isZero();

        command("stop", env("CONTAINER"));
        connection.resetConnection();
        assertThat(publisher.publish(message, Optional.empty())).isEqualTo(
                new PublishOutcome.Failed(PublishOutcome.Kind.UNAVAILABLE));
    }

    private EncodingJobMessage golden() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("docs/testing/fixtures/encoding-job-v1.json"))) root = root.getParent();
        assertThat(root).isNotNull();
        return codec.decode(Files.readAllBytes(root.resolve("docs/testing/fixtures/encoding-job-v1.json")),
                "6f1d2a4e-8c3b-4a5f-9e7d-1b2c3d4e5f60");
    }

    private static void await(BooleanSupplier predicate) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (System.nanoTime() < end) {
            if (predicate.getAsBoolean()) return;
            try { Thread.sleep(100); } catch (InterruptedException failure) {
                Thread.currentThread().interrupt(); throw new AssertionError("Broker wait interrupted");
            }
        }
        throw new AssertionError("Broker condition did not become true");
    }

    private static String env(String suffix) {
        String value = System.getenv("ENCODING_TEST_RABBITMQ_" + suffix);
        if (value == null || value.isBlank()) throw new IllegalStateException("Disposable broker configuration required");
        return value;
    }

    private static String ctl(String... args) {
        List<String> command = new ArrayList<>(List.of("exec", env("CONTAINER"), "rabbitmqctl"));
        command.addAll(List.of(args));
        return command(command.toArray(String[]::new));
    }

    private static String command(String... args) {
        List<String> command = new ArrayList<>(List.of("docker"));
        command.addAll(List.of(args));
        // Windows' native argument parser otherwise strips JSON quotes from set_policy.
        if (System.getProperty("os.name").startsWith("Windows")) {
            command.replaceAll(argument -> argument.replace("\"", "\\\""));
        }
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("Broker command timed out"); }
            byte[] output = process.getInputStream().readAllBytes();
            if (process.exitValue() != 0) throw new AssertionError("Broker command failed (details withheld)");
            return new String(output, java.nio.charset.StandardCharsets.UTF_8).strip();
        } catch (java.io.IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new AssertionError("Broker command unavailable (details withheld)");
        }
    }
}
