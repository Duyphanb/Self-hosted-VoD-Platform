package com.vodplatform.encoding.config;

import com.vodplatform.encoding.messaging.EncodingJobMessageCodec;
import com.vodplatform.encoding.messaging.EncodingJobPublisher;
import com.vodplatform.encoding.messaging.EncodingQueueTopology;
import com.vodplatform.encoding.messaging.RabbitEncodingJobPublisher;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EncodingQueueProperties.class)
public class EncodingMessagingConfiguration {
    @Bean
    EncodingJobMessageCodec encodingJobMessageCodec() { return new EncodingJobMessageCodec(); }

    @Bean
    Declarables encodingQueueDeclarables(EncodingQueueProperties properties) {
        return EncodingQueueTopology.declarables(properties.name());
    }

    @Bean
    RabbitAdmin amqpAdmin(ConnectionFactory connectionFactory, Environment environment) {
        validateConnectionSettings(environment);
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        // 4.1 uses Spring Core retry; null explicitly disables declaration retry.
        admin.setRetryTemplate(null);
        admin.setIgnoreDeclarationExceptions(false);
        return admin;
    }

    public static void validateConnectionSettings(Environment environment) {
        String host = environment.getProperty("spring.rabbitmq.host");
        if (host == null || host.isBlank()) throw new IllegalArgumentException("RabbitMQ host must not be blank");
        String username = environment.getProperty("spring.rabbitmq.username");
        String password = environment.getProperty("spring.rabbitmq.password");
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("RabbitMQ credentials must not be blank");
        }
    }

    @Bean
    EncodingJobPublisher encodingJobPublisher(RabbitTemplate template, EncodingJobMessageCodec codec,
            EncodingQueueProperties properties) {
        return new RabbitEncodingJobPublisher(template, codec, properties);
    }
}
