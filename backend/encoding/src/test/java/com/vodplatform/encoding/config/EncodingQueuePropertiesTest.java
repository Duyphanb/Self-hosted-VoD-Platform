package com.vodplatform.encoding.config;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class EncodingQueuePropertiesTest {
    @Test
    void timeoutAndQueueValidationAreFixedAndNeverEchoValues() {
        for (long timeout : new long[]{0, -1, (long) Integer.MAX_VALUE + 1}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new EncodingQueueProperties("valid", timeout))
                    .withMessage("Encoding confirm timeout must be positive milliseconds within integer range");
        }
        assertThatIllegalArgumentException().isThrownBy(() -> new EncodingQueueProperties("invalid secret/name", 5000))
                .withMessage("Encoding queue name must be 1-251 safe characters and not reserved");
    }

    @Test
    void blankConnectionInputsFailWithFixedMessages() {
        assertThatIllegalArgumentException().isThrownBy(() -> EncodingMessagingConfiguration.validateConnectionSettings(new MockEnvironment()))
                .withMessage("RabbitMQ host must not be blank");
        for (String missing : new String[]{"username", "password"}) {
            MockEnvironment env = new MockEnvironment().withProperty("spring.rabbitmq.host", "127.0.0.1")
                    .withProperty("spring.rabbitmq.username", "test-only")
                    .withProperty("spring.rabbitmq.password", "test-only");
            env.setProperty("spring.rabbitmq." + missing, " ");
            assertThatIllegalArgumentException().isThrownBy(() -> EncodingMessagingConfiguration.validateConnectionSettings(env))
                    .withMessage("RabbitMQ credentials must not be blank");
        }
    }
}
