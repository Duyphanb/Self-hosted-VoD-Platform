package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.vodplatform.encoding.config.EncodingMessagingConfiguration;
import com.vodplatform.encoding.messaging.EncodingJobMessage;
import com.vodplatform.encoding.messaging.EncodingJobPublisher;
import com.vodplatform.encoding.messaging.PublishOutcome;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;
import org.springframework.boot.amqp.health.RabbitHealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class TopologyFailureTests {
    @Test
    void repeatedPublishesCannotBypassDeclarationFailureAndSuccessfulRedeclarationRecovers() throws Exception {
        var nativeFactory = mock(com.rabbitmq.client.ConnectionFactory.class);
        Connection nativeConnection = mock(Connection.class);
        Channel channel = mock(Channel.class);
        when(nativeFactory.newConnection(nullable(ExecutorService.class), anyString())).thenReturn(nativeConnection);
        when(nativeConnection.isOpen()).thenReturn(true);
        when(nativeConnection.getServerProperties()).thenReturn(Map.of("version", "fixture-version"));
        when(nativeConnection.createChannel()).thenReturn(channel);
        when(channel.isOpen()).thenReturn(true);
        when(channel.getConnection()).thenReturn(nativeConnection);
        when(channel.queueDeclare(anyString(), anyBoolean(), anyBoolean(), anyBoolean(), anyMap()))
                .thenThrow(new IOException("fixture declaration failure"));
        var factory = new CachingConnectionFactory(nativeFactory);
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of(
                    "spring.rabbitmq.host", "127.0.0.1", "spring.rabbitmq.username", "fixture",
                    "spring.rabbitmq.password", "fixture-only", "encoding.queue.name", "fixture-queue",
                    "encoding.queue.publish-confirm-timeout-ms", "20")));
            context.registerBean(CachingConnectionFactory.class, () -> factory);
            context.registerBean(RabbitProperties.class, RabbitProperties::new);
            context.registerBean(RabbitTemplate.class, () -> new RabbitTemplate(factory));
            context.register(EncodingMessagingConfiguration.class);
            context.refresh();
            var publisher = context.getBean(EncodingJobPublisher.class);
            var job = new EncodingJobMessage(UUID.randomUUID(), UUID.randomUUID(), 1);
            assertThat(publisher.publish(job, Optional.empty())).isInstanceOf(PublishOutcome.Failed.class);
            assertThat(publisher.publish(job, Optional.empty())).isInstanceOf(PublishOutcome.Failed.class);
            verify(channel, times(2)).queueDeclare(anyString(), anyBoolean(), anyBoolean(), anyBoolean(), anyMap());
            var health = new RabbitHealthIndicator(context.getBean(RabbitTemplate.class));
            assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
            // No manual reset: a later connection must attempt declaration again and may recover.
            doReturn(mock(com.rabbitmq.client.AMQP.Queue.DeclareOk.class)).when(channel)
                    .queueDeclare(anyString(), anyBoolean(), anyBoolean(), anyBoolean(), anyMap());
            assertThat(health.health().getStatus()).isEqualTo(Status.UP);
            verify(channel, times(5)).queueDeclare(anyString(), anyBoolean(), anyBoolean(), anyBoolean(), anyMap());
        }
    }
}
