package com.vodplatform.worker.queue.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ShutdownSignalException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Opt-in declaration on a disposable Compose broker, kept for the backend cross-declaration test.
 * This class adds no worker runtime configuration or listener; Issue 5.1 owns that wiring.
 */
@EnabledIf("brokerConfigured")
class EncodingQueueBrokerTests {
    static boolean brokerConfigured() {
        return Boolean.parseBoolean(System.getenv("ENCODING_TEST_RABBITMQ_REQUIRED"))
                || System.getenv("ENCODING_TEST_RABBITMQ_HOST") != null;
    }

    @Test
    void workerDeclaresTheContractTopologyOnTheOwnedBroker() throws Exception {
        String host = required("HOST");
        if (!"127.0.0.1".equals(host)) throw new IllegalStateException("Broker tests require a loopback host");
        int port;
        try {
            port = Integer.parseInt(required("PORT"));
        } catch (NumberFormatException ignored) {
            throw new IllegalStateException("Broker test port is invalid");
        }
        if (port < 1 || port > 65535) throw new IllegalStateException("Broker test port is invalid");
        String username = required("USERNAME");
        String password = required("PASSWORD");
        String container = required("CONTAINER");
        verifyDisposableProject(container);
        String queue = System.getenv("ENCODING_TEST_RABBITMQ_QUEUE");
        queue = EncodingQueueTopology.validateQueueName(queue == null ? "video-encoding-queue" : queue);

        CachingConnectionFactory connectionFactory = new CachingConnectionFactory(host, port);
        connectionFactory.setUsername(username);
        connectionFactory.setPassword(password);
        connectionFactory.setConnectionTimeout(5000);
        connectionFactory.getRabbitConnectionFactory().setAutomaticRecoveryEnabled(false);
        connectionFactory.getRabbitConnectionFactory().setChannelRpcTimeout(5000);
        try {
            RabbitAdmin admin = new RabbitAdmin(connectionFactory);
            admin.setAutoStartup(false);
            var declarations = EncodingQueueTopology.declarables(queue);
            for (Exchange exchange : declarations.getDeclarablesByType(Exchange.class)) admin.declareExchange(exchange);
            for (Queue declaration : declarations.getDeclarablesByType(Queue.class)) admin.declareQueue(declaration);
            for (Binding binding : declarations.getDeclarablesByType(Binding.class)) admin.declareBinding(binding);
            assertBrokerMetadata(container, queue);
            var connection = connectionFactory.createConnection();
            try (Channel channel = connection.createChannel(false)) {
                for (String name : new String[] {queue, queue + ".dlq"}) {
                    var info = channel.queueDeclarePassive(name);
                    assertThat(info.getQueue()).isEqualTo(name);
                    assertThat(info.getMessageCount()).isZero();
                    assertThat(info.getConsumerCount()).isZero();
                }
            } finally {
                connection.close();
            }
            // AMQP rejects mismatched declarations without changing the existing queue.
            var mismatchConnection = connectionFactory.getRabbitConnectionFactory().newConnection();
            try (mismatchConnection) {
                Channel mismatchChannel = mismatchConnection.createChannel();
                String sourceName = queue;
                Throwable mismatch = catchThrowable(() -> mismatchChannel.queueDeclare(
                        sourceName, false, false, false,
                        Map.of("x-dead-letter-exchange", sourceName + ".dlx",
                                "x-dead-letter-routing-key", sourceName + ".dlq")));
                assertThat(mismatch).isNotNull();
                ShutdownSignalException shutdown = null;
                for (Throwable cause = mismatch; cause != null; cause = cause.getCause()) {
                    if (cause instanceof ShutdownSignalException signal) shutdown = signal;
                }
                assertThat(shutdown).isNotNull();
                assertThat(shutdown.getReason()).isInstanceOf(com.rabbitmq.client.AMQP.Channel.Close.class);
                assertThat(((com.rabbitmq.client.AMQP.Channel.Close) shutdown.getReason()).getReplyCode()).isEqualTo(406);
            }
            assertBrokerMetadata(container, queue);
        } catch (Exception failure) {
            // Never attach a client exception that might include generated credentials.
            throw new AssertionError("Worker broker declaration failed: " + failure.getClass().getSimpleName());
        } finally {
            connectionFactory.destroy();
        }
    }

    private static String required(String key) {
        String value = System.getenv("ENCODING_TEST_RABBITMQ_" + key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Broker test setting is missing: " + key);
        return value;
    }

    private static void assertBrokerMetadata(String container, String queue) throws Exception {
        JsonNode queues = ctl(container, "list_queues", "name", "durable", "auto_delete", "exclusive",
                "arguments", "consumers", "type", "--formatter", "json");
        for (String name : new String[] {queue, queue + ".dlq"}) {
            JsonNode row = named(queues, "name", name);
            assertThat(row.get("durable").booleanValue()).isTrue();
            assertThat(row.get("auto_delete").booleanValue()).isFalse();
            assertThat(row.get("exclusive").booleanValue()).isFalse();
            assertThat(row.get("consumers").intValue()).isZero();
            assertThat(row.get("type").asString()).isEqualTo("classic");
            assertThat(arguments(row.get("arguments"))).isEqualTo(name.equals(queue)
                    ? Map.of("x-dead-letter-exchange", queue + ".dlx", "x-dead-letter-routing-key", queue + ".dlq")
                    : Map.of());
        }
        JsonNode dlx = named(ctl(container, "list_exchanges", "name", "type", "durable", "auto_delete",
                "internal", "arguments", "--formatter", "json"), "name", queue + ".dlx");
        assertThat(dlx.get("type").asString()).isEqualTo("direct");
        assertThat(dlx.get("durable").booleanValue()).isTrue();
        assertThat(dlx.get("auto_delete").booleanValue()).isFalse();
        assertThat(dlx.get("internal").booleanValue()).isFalse();
        assertThat(arguments(dlx.get("arguments"))).isEmpty();
        JsonNode bindings = ctl(container, "list_bindings", "source_name", "source_kind", "destination_name",
                "destination_kind", "routing_key", "arguments", "--formatter", "json");
        JsonNode binding = named(bindings, "source_name", queue + ".dlx");
        assertThat(binding.get("source_kind").asString()).isEqualTo("exchange");
        assertThat(binding.get("destination_name").asString()).isEqualTo(queue + ".dlq");
        assertThat(binding.get("destination_kind").asString()).isEqualTo("queue");
        assertThat(binding.get("routing_key").asString()).isEqualTo(queue + ".dlq");
        assertThat(arguments(binding.get("arguments"))).isEmpty();
    }

    private static JsonNode named(JsonNode rows, String field, String value) {
        assertThat(rows.isArray()).isTrue();
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode row : rows) if (value.equals(row.get(field).asString())) matches.add(row);
        assertThat(matches).hasSize(1);
        return matches.getFirst();
    }

    private static Map<String, String> arguments(JsonNode node) {
        Map<String, String> values = new HashMap<>();
        if (node.isObject()) {
            for (var property : node.properties()) values.put(property.getKey(), property.getValue().asString());
        } else {
            assertThat(node.isArray()).isTrue();
            // RabbitMQ formatter versions also represent AMQP tables as key/type/value tuples.
            for (JsonNode entry : node) {
                assertThat(entry.size()).isEqualTo(3);
                values.put(entry.get(0).asString(), entry.get(2).asString());
            }
        }
        return values;
    }

    private static JsonNode ctl(String container, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("exec", container, "rabbitmqctl", "--quiet"));
        command.addAll(List.of(arguments));
        return JsonMapper.builder().build().readTree(docker(command.toArray(String[]::new)));
    }

    private static String docker(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("docker"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Broker audit command timed out");
        }
        if (process.exitValue() != 0) throw new IllegalStateException("Broker audit command failed (details withheld)");
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
    }

    /** Must pass before opening a connection or declaring any broker object. */
    private static void verifyDisposableProject(String container) throws Exception {
        if (!container.matches("[a-f0-9]{12,64}")) throw new IllegalStateException("Broker container id is invalid");
        Process inspection = new ProcessBuilder("docker", "inspect", "--format",
                "{{json .Config.Labels}}", container)
                .redirectErrorStream(true).start();
        if (!inspection.waitFor(20, TimeUnit.SECONDS)) {
            inspection.destroyForcibly();
            throw new IllegalStateException("Broker ownership inspection timed out");
        }
        if (inspection.exitValue() != 0) {
            throw new IllegalStateException("Broker ownership inspection failed (details withheld)");
        }
        JsonNode labels;
        try {
            labels = JsonMapper.builder().build().readTree(inspection.getInputStream().readNBytes(16_384));
        } catch (RuntimeException ignored) {
            throw new IllegalStateException("Broker ownership labels are invalid (details withheld)");
        }
        JsonNode project = labels == null ? null : labels.get("com.docker.compose.project");
        if (project == null || !project.isString() || !project.asString().matches("vod-auth-audit-[a-z0-9][a-z0-9_-]*")) {
            throw new IllegalStateException("Broker tests require an owned disposable Compose project");
        }
    }
}
