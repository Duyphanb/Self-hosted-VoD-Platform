package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.ShutdownSignalException;
import com.vodplatform.encoding.messaging.EncodingQueueTopology;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * #106 opt-in check on a disposable Compose broker that rabbitmq-publisher-smoke.py owns: the
 * scoped backend and worker users declare the identical ADR-010 topology, the backend publishes
 * jobs and the worker consumes or dead-letters them, and every other operation is refused.
 */
@EnabledIf("configured")
class EncodingBrokerPermissionTests {
    private static final AMQP.BasicProperties PERSISTENT = new AMQP.BasicProperties.Builder()
            .contentType("application/json").deliveryMode(2).build();
    private static final byte[] BODY = "{\"audit\":106}".getBytes(StandardCharsets.UTF_8);

    static boolean configured() {
        return System.getenv("ENCODING_TEST_RABBITMQ_BACKEND_USERNAME") != null;
    }

    @Test
    void scopedIdentitiesCanOnlyUseTheirSideOfTheEncodingQueue() throws Exception {
        String container = env("CONTAINER");
        verifyDisposableBroker(container);
        String queue = EncodingQueueTopology.validateQueueName(System.getenv().getOrDefault(
                "ENCODING_TEST_RABBITMQ_QUEUE", "video-encoding-queue"));
        try (Connection backend = connect("BACKEND", "/"); Connection worker = connect("WORKER", "/")) {
            declareTopology(backend, queue);
            declareTopology(worker, queue);

            Channel publisher = backend.createChannel();
            publisher.confirmSelect();
            publisher.basicPublish("", queue, true, PERSISTENT, BODY);
            publisher.waitForConfirmsOrDie(5000);
            Channel consumer = worker.createChannel();
            GetResponse job = consumer.basicGet(queue, false);
            assertThat(job).isNotNull();
            // A poison message dead-letters with no extra permission (ADR-010 reject without requeue).
            consumer.basicReject(job.getEnvelope().getDeliveryTag(), false);
            awaitDeadLetter(worker, queue + ".dlq");

            assertRefused(worker, channel -> publishConfirmed(channel, "", queue));
            assertRefused(backend, channel -> publishConfirmed(channel, "amq.direct", queue));
            assertRefused(worker, channel -> publishConfirmed(channel, "amq.topic", queue));
            assertRefused(backend, channel -> channel.basicGet(queue + ".dlq", true));
            assertRefused(worker, channel -> channel.basicGet(queue + ".dlq", true));
            assertRefused(worker, channel -> channel.queuePurge(queue + ".dlq"));
            assertRefused(backend, channel -> channel.queueDeclare("audit-106-other", true, false, false, null));
            assertRefused(worker, channel -> channel.queueDeclare("audit-106-other", true, false, false, null));
            assertRefused(backend, channel -> channel.exchangeDeclare("audit-106-other", "direct", true));
            assertRefused(worker, channel -> channel.queueBind(queue + ".dlq", "amq.direct", "audit-106"));
        } catch (AssertionError failure) {
            throw failure;
        } catch (Exception failure) {
            // Never attach a client exception that might include generated credentials.
            throw new AssertionError("Broker permission check failed: " + failure.getClass().getSimpleName());
        }

        // Neither application user has a management tag or access to another virtual host.
        Map<String, List<String>> tags = new HashMap<>();
        for (JsonNode user : ctl(container, "list_users", "--formatter", "json")) {
            List<String> values = new ArrayList<>();
            JsonNode node = user.get("tags");
            if (node.isArray()) node.forEach(tag -> values.add(tag.asString()));
            else if (!node.asString().isBlank()) values.add(node.asString());
            tags.put(user.get("user").asString(), values);
        }
        assertThat(tags.get(env("BACKEND_USERNAME"))).isEmpty();
        assertThat(tags.get(env("WORKER_USERNAME"))).isEmpty();
        ctl(container, "add_vhost", "audit-106-isolated");
        try {
            for (String identity : new String[] {"BACKEND", "WORKER"}) {
                assertThat(catchThrowable(() -> connect(identity, "audit-106-isolated").close())).isNotNull();
            }
        } finally {
            ctl(container, "delete_vhost", "audit-106-isolated");
        }
    }

    private static void declareTopology(Connection connection, String queue) throws Exception {
        var declarations = EncodingQueueTopology.declarables(queue);
        try (Channel channel = connection.createChannel()) {
            for (Exchange exchange : declarations.getDeclarablesByType(Exchange.class)) {
                channel.exchangeDeclare(exchange.getName(), exchange.getType(), exchange.isDurable(),
                        exchange.isAutoDelete(), exchange.getArguments());
            }
            for (Queue declaration : declarations.getDeclarablesByType(Queue.class)) {
                channel.queueDeclare(declaration.getName(), declaration.isDurable(), declaration.isExclusive(),
                        declaration.isAutoDelete(), declaration.getArguments());
            }
            for (Binding binding : declarations.getDeclarablesByType(Binding.class)) {
                channel.queueBind(binding.getDestination(), binding.getExchange(), binding.getRoutingKey());
            }
        }
    }

    private static void publishConfirmed(Channel channel, String exchange, String routingKey) throws Exception {
        channel.confirmSelect();
        channel.basicPublish(exchange, routingKey, false, PERSISTENT, BODY);
        channel.waitForConfirmsOrDie(5000);
    }

    private static void awaitDeadLetter(Connection connection, String deadLetterQueue) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (Channel channel = connection.createChannel()) {
            while (channel.queueDeclarePassive(deadLetterQueue).getMessageCount() != 1) {
                if (System.nanoTime() > end) throw new AssertionError("Rejected job did not reach the DLQ");
                Thread.sleep(100);
            }
        }
    }

    private interface ChannelAction {
        void run(Channel channel) throws Exception;
    }

    /** The broker must close the channel with 403 ACCESS_REFUSED, not fail for another reason. */
    private static void assertRefused(Connection connection, ChannelAction action) throws Exception {
        Channel channel = connection.createChannel();
        Throwable failure = catchThrowable(() -> action.run(channel));
        ShutdownSignalException shutdown = null;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ShutdownSignalException signal) shutdown = signal;
        }
        if (shutdown == null && !channel.isOpen()) shutdown = channel.getCloseReason();
        assertThat(shutdown).as("operation refused").isNotNull();
        assertThat(shutdown.getReason()).isInstanceOf(AMQP.Channel.Close.class);
        assertThat(((AMQP.Channel.Close) shutdown.getReason()).getReplyCode()).isEqualTo(403);
    }

    private static Connection connect(String identity, String virtualHost) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(env("HOST"));
        factory.setPort(Integer.parseInt(env("PORT")));
        factory.setUsername(env(identity + "_USERNAME"));
        factory.setPassword(env(identity + "_PASSWORD"));
        factory.setVirtualHost(virtualHost);
        factory.setConnectionTimeout(5000);
        factory.setHandshakeTimeout(5000);
        factory.setChannelRpcTimeout(5000);
        factory.setAutomaticRecoveryEnabled(false);
        return factory.newConnection();
    }

    private static String env(String suffix) {
        String value = System.getenv("ENCODING_TEST_RABBITMQ_" + suffix);
        if (value == null || value.isBlank()) throw new IllegalStateException("Broker test setting is missing: " + suffix);
        return value;
    }

    private static JsonNode ctl(String container, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("exec", container, "rabbitmqctl", "--quiet"));
        command.addAll(List.of(arguments));
        String output = docker(command.toArray(String[]::new));
        return output.isBlank() ? JsonMapper.builder().build().createArrayNode()
                : JsonMapper.builder().build().readTree(output);
    }

    private static String docker(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("docker"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Broker audit command timed out");
        }
        byte[] output = process.getInputStream().readAllBytes();
        if (process.exitValue() != 0) throw new IllegalStateException("Broker audit command failed (details withheld)");
        return new String(output, StandardCharsets.UTF_8).strip();
    }

    /** Must pass before connecting: only a loopback port of an owned vod-auth-audit-* broker. */
    private static void verifyDisposableBroker(String container) throws Exception {
        if (!container.matches("[a-f0-9]{12,64}")) throw new IllegalStateException("Broker container id is invalid");
        if (!"127.0.0.1".equals(env("HOST"))) throw new IllegalStateException("Broker tests require a loopback host");
        JsonNode labels = JsonMapper.builder().build().readTree(docker("inspect", "--format",
                "{{json .Config.Labels}}", container));
        JsonNode project = labels == null ? null : labels.get("com.docker.compose.project");
        if (project == null || !project.isString() || !project.asString().matches("vod-auth-audit-[a-z0-9][a-z0-9_-]*")) {
            throw new IllegalStateException("Broker tests require an owned disposable Compose project");
        }
        assertThat(docker("port", container, "5672/tcp").trim()).isEqualTo("127.0.0.1:" + env("PORT"));
    }
}
