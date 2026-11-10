package com.vodplatform.worker.queue.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class WorkerQueueConfiguration {
    private static final Logger log = LoggerFactory.getLogger(WorkerQueueConfiguration.class);
    private static final int BROKER_TIMEOUT_MS = 5_000;

    @Bean
    CachingConnectionFactory workerRabbitConnectionFactory(Environment environment) {
        String host = required(environment, "RABBITMQ_HOST");
        String username = required(environment, "RABBITMQ_USERNAME");
        String password = required(environment, "RABBITMQ_PASSWORD");
        int port = environment.getProperty("RABBITMQ_PORT", Integer.class, 5672);
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("RABBITMQ_PORT is out of range");
        CachingConnectionFactory connection = new CachingConnectionFactory(host, port);
        connection.setUsername(username);
        connection.setPassword(password);
        connection.setConnectionTimeout(BROKER_TIMEOUT_MS);
        connection.getRabbitConnectionFactory().setHandshakeTimeout(BROKER_TIMEOUT_MS);
        connection.getRabbitConnectionFactory().setChannelRpcTimeout(BROKER_TIMEOUT_MS);
        connection.getRabbitConnectionFactory().setAutomaticRecoveryEnabled(false);
        return connection;
    }

    @Bean
    Declarables workerEncodingQueue(Environment environment) {
        return EncodingQueueTopology.declarables(queueName(environment));
    }

    @Bean
    RabbitAdmin workerRabbitAdmin(CachingConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory) {
            @Override
            public void initialize() {
                try {
                    super.initialize();
                } catch (RuntimeException failure) {
                    connectionFactory.resetConnection();
                    throw failure;
                }
            }
        };
        admin.setRetryTemplate(null);
        admin.setIgnoreDeclarationExceptions(false);
        return admin;
    }

    @Bean
    SimpleMessageListenerContainer encodingJobListenerContainer(CachingConnectionFactory connectionFactory,
            Environment environment, ObjectProvider<EncodingJobProcessor> processors) {
        int concurrency = requireOne(environment, "WORKER_CONCURRENCY");
        int prefetch = requireOne(environment, "WORKER_PREFETCH");
        var container = new SimpleMessageListenerContainer(connectionFactory) {
            @Override
            public void start() {
                if (processors.getIfAvailable() == null) {
                    throw new IllegalStateException("Encoding job processor is not configured");
                }
                super.start();
            }
        };
        container.setQueueNames(queueName(environment));
        container.setConcurrentConsumers(concurrency);
        container.setMaxConcurrentConsumers(concurrency);
        container.setPrefetchCount(prefetch);
        container.setAcknowledgeMode(AcknowledgeMode.AUTO);
        container.setDefaultRequeueRejected(false);
        // Issue 5.7 starts the container only after startup recovery succeeds.
        container.setAutoStartup(false);
        container.setMessageListener(new EncodingJobListener(job -> processors.getObject().process(job)));
        container.setErrorHandler(failure -> log.warn("Encoding job listener failed"));
        return container;
    }

    private static String queueName(Environment environment) {
        return required(environment, "RABBITMQ_ENCODING_QUEUE");
    }

    private static int requireOne(Environment environment, String name) {
        if (!"1".equals(required(environment, name))) throw new IllegalArgumentException(name + " must be 1");
        return 1;
    }

    private static String required(Environment environment, String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must be set");
        return value;
    }
}
