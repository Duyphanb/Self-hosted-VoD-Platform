package com.vodplatform.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vodplatform.encoding.config.EncodingMessagingConfiguration;
import com.vodplatform.encoding.config.EncodingQueueProperties;
import com.vodplatform.encoding.messaging.EncodingJobMessage;
import com.vodplatform.encoding.messaging.EncodingJobPublisher;
import com.vodplatform.encoding.messaging.PublishOutcome;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;
import org.springframework.boot.amqp.health.RabbitHealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** Production YAML and real Boot AMQP wiring; every connection target is closed loopback. */
class RabbitConfigurationTests {

    @Test
    void environmentValuesReachProductionConnectionTopologyAndConfirmConfiguration() {
        configured().withPropertyValues("RABBITMQ_ENCODING_QUEUE=custom-encoding",
                        "ENCODING_PUBLISH_CONFIRM_TIMEOUT_MS=1234")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    RabbitProperties rabbit = context.getBean(RabbitProperties.class);
                    assertEquals("127.0.0.1", rabbit.getHost());
                    assertEquals(Integer.valueOf(1), rabbit.getPort());
                    assertEquals("fixture-publisher", rabbit.getUsername());
                    assertEquals("fixture-only-password", rabbit.getPassword());
                    assertEquals(Duration.ofSeconds(5), rabbit.getConnectionTimeout());
                    assertEquals(Duration.ofSeconds(5), rabbit.getChannelRpcTimeout());
                    assertEquals(CachingConnectionFactory.ConfirmType.CORRELATED, rabbit.getPublisherConfirmType());
                    assertTrue(rabbit.isPublisherReturns());
                    CachingConnectionFactory connection = context.getBean(CachingConnectionFactory.class);
                    assertEquals(5000, connection.getRabbitConnectionFactory().getHandshakeTimeout());
                    assertTrue(connection.isPublisherConfirms());
                    assertTrue(connection.isPublisherReturns());
                    assertTrue(context.getBean(RabbitTemplate.class)
                            .isMandatoryFor(new Message(new byte[0], new MessageProperties())));
                    EncodingQueueProperties queue = context.getBean(EncodingQueueProperties.class);
                    assertEquals("custom-encoding", queue.name());
                    assertEquals(1234, queue.publishConfirmTimeoutMs());
                    Queue source = context.getBean(Declarables.class).getDeclarablesByType(Queue.class)
                            .stream().filter(candidate -> candidate.getName().equals(queue.name()))
                            .findFirst().orElseThrow();
                    assertEquals("custom-encoding.dlx", source.getArguments().get("x-dead-letter-exchange"));
                    assertEquals("custom-encoding.dlq", source.getArguments().get("x-dead-letter-routing-key"));
                });
    }

    @Test
    void yamlSuppliesFrozenQueueAndConfirmWaitDefaults() {
        configured().run(context -> {
            assertNull(context.getStartupFailure());
            EncodingQueueProperties properties = context.getBean(EncodingQueueProperties.class);
            assertEquals("video-encoding-queue", properties.name());
            assertEquals(5000, properties.publishConfirmTimeoutMs());
            assertEquals(Duration.ofSeconds(5), context.getBean(EncodingJobPublisher.class).confirmTimeout());
        });
    }

    @Test
    void missingEnvironmentCannotFallBackToLocalhostOrGuest() {
        yamlRunner().withInitializer(context -> {
            // A failed AssertableApplicationContext disallows getEnvironment(); inspect before refresh.
            assertEquals("", context.getEnvironment().getProperty("spring.rabbitmq.host"));
            assertEquals("", context.getEnvironment().getProperty("spring.rabbitmq.username"));
            assertEquals("", context.getEnvironment().getProperty("spring.rabbitmq.password"));
        }).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertEquals("RabbitMQ host must not be blank", rootCause(context.getStartupFailure()).getMessage());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"RABBITMQ_HOST", "RABBITMQ_USERNAME", "RABBITMQ_PASSWORD"})
    void blankConnectionSettingsFailStartupWithFixedMessages(String setting) {
        configured().withPropertyValues(setting + "= ").run(context -> {
            assertNotNull(context.getStartupFailure());
            String message = setting.equals("RABBITMQ_HOST")
                    ? "RabbitMQ host must not be blank" : "RabbitMQ credentials must not be blank";
            assertEquals(message, rootCause(context.getStartupFailure()).getMessage());
        });
    }

    @Test
    void realClosedLoopbackConnectionMapsToUnavailableAndReadinessDown() throws Exception {
        int port;
        try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = reservation.getLocalPort();
        }
        configured().withPropertyValues("RABBITMQ_PORT=" + port,
                        "spring.rabbitmq.connection-timeout=100ms", "spring.rabbitmq.channel-rpc-timeout=100ms")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertInstanceOf(CachingConnectionFactory.class,
                            context.getBean(org.springframework.amqp.rabbit.connection.ConnectionFactory.class));
                    assertEquals(100, context.getBean(CachingConnectionFactory.class)
                            .getRabbitConnectionFactory().getHandshakeTimeout());
                    EncodingJobPublisher publisher = context.getBean(EncodingJobPublisher.class);
                    PublishOutcome outcome = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> publisher.publish(
                            new EncodingJobMessage(UUID.randomUUID(), UUID.randomUUID(), 1), Optional.empty()));
                    assertEquals(PublishOutcome.Kind.UNAVAILABLE,
                            assertInstanceOf(PublishOutcome.Failed.class, outcome).kind());
                    assertEquals(Status.DOWN, assertTimeoutPreemptively(Duration.ofSeconds(3),
                            () -> new RabbitHealthIndicator(context.getBean(RabbitTemplate.class)).health()).getStatus());
                });
    }

    private static ApplicationContextRunner configured() {
        return yamlRunner().withPropertyValues("RABBITMQ_HOST=127.0.0.1", "RABBITMQ_PORT=1",
                "RABBITMQ_USERNAME=fixture-publisher", "RABBITMQ_PASSWORD=fixture-only-password");
    }

    private static ApplicationContextRunner yamlRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
                .withUserConfiguration(EncodingMessagingConfiguration.class)
                .withInitializer(context -> {
                    // Never inherit a developer/CI broker target or credentials for this unit context.
                    context.getEnvironment().getPropertySources()
                            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    context.getEnvironment().getPropertySources()
                            .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    try {
                        new YamlPropertySourceLoader().load("production-rabbit-yaml", new ClassPathResource("application.yml"))
                                .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                });
    }

    private static Throwable rootCause(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        return failure;
    }
}
