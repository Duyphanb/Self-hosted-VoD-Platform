package com.vodplatform.worker.queue.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.rabbitmq.client.Channel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in D21 broker evidence. The gated handler models a delivery waiting to return after persistence. */
@EnabledIfEnvironmentVariable(named = "ENCODING_TEST_RABBITMQ_LONG_HOLD", matches = "true")
class EncodingQueueLongHoldBrokerTests {
    private static final String QUEUE = "video-encoding-queue";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int SAMPLE_SECONDS = 61;

    @Test
    @Timeout(260)
    void classicDeliverySurvivesThreeTimeoutIntervalsAndReleasesTheNextOnlyOnAck() throws Exception {
        String broker = required("CONTAINER");
        assertThat(broker).matches("[a-f0-9]{12,64}");
        assertThat(docker("inspect", "--format", "{{.Name}}", broker)).matches("/s5w6-191-hold-[a-z0-9]+");
        JsonNode labels = JSON.readTree(docker("inspect", "--format", "{{json .Config.Labels}}", broker));
        assertThat(labels.path("com.vodplatform.s5w6.consumer-hold").asString()).isEqualTo("true");
        int port = Integer.parseInt(required("PORT"));
        assertThat(docker("port", broker, "5672/tcp")).isEqualTo("127.0.0.1:" + port);
        assertThat(docker("exec", broker, "rabbitmqctl", "-q", "version")).startsWith("4.4.");
        assertThat(docker("exec", broker, "rabbitmqctl", "-q", "eval",
                "application:get_env(rabbit, consumer_timeout).")).isEqualTo("{ok,60000}");

        CachingConnectionFactory connection = new CachingConnectionFactory("127.0.0.1", port);
        connection.setUsername(required("USERNAME"));
        connection.setPassword(required("PASSWORD"));
        connection.setConnectionTimeout(5000);
        connection.getRabbitConnectionFactory().setAutomaticRecoveryEnabled(false);
        connection.getRabbitConnectionFactory().setChannelRpcTimeout(5000);

        CountDownLatch persisted = new CountDownLatch(1);
        BlockingQueue<Delivery> observed = new LinkedBlockingQueue<>();
        SimpleMessageListenerContainer listener = new SimpleMessageListenerContainer(connection);
        listener.setQueueNames(QUEUE);
        listener.setAutoStartup(false);
        listener.setConcurrentConsumers(1);
        listener.setMaxConcurrentConsumers(1);
        listener.setPrefetchCount(1);
        listener.setAcknowledgeMode(AcknowledgeMode.AUTO);
        listener.setDefaultRequeueRejected(false);
        listener.setMessageListener((ChannelAwareMessageListener) (message, channel) -> {
            var properties = message.getMessageProperties();
            observed.add(new Delivery(properties.getMessageId(), properties.isRedelivered(),
                    properties.getConsumerTag(), channel));
            if ("held".equals(properties.getMessageId()) && !persisted.await(230, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test never completed the held delivery");
            }
        });

        try {
            declareTopology(connection);
            RabbitTemplate template = new RabbitTemplate(connection);
            template.send("", QUEUE, message("held"));
            template.send("", QUEUE, message("next"));
            listener.start();

            Delivery first = observed.poll(10, TimeUnit.SECONDS);
            assertThat(first).isNotNull();
            assertThat(first.id()).isEqualTo("held");
            assertThat(first.redelivered()).isFalse();
            String channelPid = assertHeld(broker, first);
            for (int interval = 0; interval < 3; interval++) {
                assertThat(observed.poll(SAMPLE_SECONDS, TimeUnit.SECONDS))
                        .as("no delivery while the first handler is waiting").isNull();
                assertThat(assertHeld(broker, first)).isEqualTo(channelPid);
            }

            persisted.countDown();
            Delivery second = observed.poll(10, TimeUnit.SECONDS);
            assertThat(second).isNotNull();
            assertThat(second.id()).isEqualTo("next");
            assertThat(second.redelivered()).isFalse();
            assertThat(second.tag()).isEqualTo(first.tag());
            assertThat(second.channel()).isSameAs(first.channel());
            assertThat(second.channel().isOpen()).isTrue();
            assertThat(observed).isEmpty();
        } catch (Exception failure) {
            // Client exceptions can include connection details. Preserve assertion failures, but withhold those details.
            throw new AssertionError("Worker long-hold broker test failed: " + failure.getClass().getSimpleName());
        } finally {
            persisted.countDown();
            listener.destroy();
            connection.destroy();
        }
    }

    private static Message message(String id) {
        return MessageBuilder.withBody(new byte[0]).setMessageId(id)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT).build();
    }

    private static void declareTopology(CachingConnectionFactory connection) {
        RabbitAdmin admin = new RabbitAdmin(connection);
        admin.setAutoStartup(false);
        var declarations = EncodingQueueTopology.declarables(QUEUE);
        for (Exchange exchange : declarations.getDeclarablesByType(Exchange.class)) admin.declareExchange(exchange);
        for (Queue queue : declarations.getDeclarablesByType(Queue.class)) admin.declareQueue(queue);
        for (Binding binding : declarations.getDeclarablesByType(Binding.class)) admin.declareBinding(binding);
    }

    private static String assertHeld(String broker, Delivery delivery) throws Exception {
        assertThat(delivery.channel().isOpen()).isTrue();
        JsonNode queue = namedRow(ctl(broker, "list_queues", "name", "type", "messages_ready",
                "messages_unacknowledged", "consumers"), "name", QUEUE);
        assertThat(queue.path("type").asString()).isEqualTo("classic");
        assertThat(queue.path("messages_ready").asInt()).isEqualTo(1);
        assertThat(queue.path("messages_unacknowledged").asInt()).isEqualTo(1);
        assertThat(queue.path("consumers").asInt()).isEqualTo(1);

        JsonNode consumer = namedRow(ctl(broker, "list_consumers", "queue_name", "channel_pid", "consumer_tag",
                "ack_required", "prefetch_count"), "queue_name", QUEUE);
        assertThat(consumer.path("consumer_tag").asString()).isEqualTo(delivery.tag());
        assertThat(consumer.path("ack_required").asBoolean()).isTrue();
        assertThat(consumer.path("prefetch_count").asInt()).isEqualTo(1);
        String channelPid = consumer.path("channel_pid").asString();
        assertThat(channelPid).isNotBlank();
        return channelPid;
    }

    private static JsonNode namedRow(JsonNode rows, String field, String value) {
        assertThat(rows.isArray()).isTrue();
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode row : rows) if (value.equals(row.path(field).asString())) matches.add(row);
        assertThat(matches).hasSize(1);
        return matches.getFirst();
    }

    private static JsonNode ctl(String broker, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("exec", broker, "rabbitmqctl", "--quiet"));
        command.addAll(List.of(arguments));
        command.addAll(List.of("--formatter", "json"));
        return JSON.readTree(docker(command.toArray(String[]::new)));
    }

    private static String docker(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("docker"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Broker audit command timed out");
        }
        if (process.exitValue() != 0) throw new IllegalStateException("Broker audit command failed (details withheld)");
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
    }

    private static String required(String key) {
        String value = System.getenv("ENCODING_TEST_RABBITMQ_LONG_HOLD_" + key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Broker test setting is missing: " + key);
        return value;
    }

    private record Delivery(String id, boolean redelivered, String tag, Channel channel) { }
}
