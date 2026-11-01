package com.vodplatform.encoding.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.vodplatform.encoding.service.EncodingRelay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;

import java.time.Duration;

class EncodingRelayConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(EncodingRelayConfiguration.class)
                    .withBean(EncodingRelay.class, () -> mock(EncodingRelay.class));

    @Test
    void unsetPropertyRegistersExactlyOneTenSecondFixedDelayTask() {
        assertTask(runner, 10);
    }

    @Test
    void schedulerUsesValidatedConfiguredIntervalAndSameInitialDelay() {
        assertTask(runner.withPropertyValues("encoding.relay.interval-seconds=42"), 42);
    }

    private void assertTask(ApplicationContextRunner contextRunner, long seconds) {
        contextRunner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(EncodingRelayProperties.class).intervalSeconds())
                            .isEqualTo(seconds);
                    var tasks =
                            context.getBean(ScheduledAnnotationBeanPostProcessor.class)
                                    .getScheduledTasks();
                    assertThat(tasks).hasSize(1);
                    ScheduledTask scheduled = tasks.iterator().next();
                    assertThat(scheduled.getTask()).isInstanceOf(FixedDelayTask.class);
                    FixedDelayTask task = (FixedDelayTask) scheduled.getTask();
                    assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofSeconds(seconds));
                    assertThat(task.getInitialDelayDuration())
                            .isEqualTo(Duration.ofSeconds(seconds));
                });
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 86401, Long.MAX_VALUE})
    void rejectsOutOfRangeValuesAtBindingAndDirectConstruction(long seconds) {
        assertThatThrownBy(() -> new EncodingRelayProperties(seconds))
                .isInstanceOf(IllegalArgumentException.class);
        runner.withPropertyValues("encoding.relay.interval-seconds=" + seconds)
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
                        });
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 86400})
    void acceptsInclusiveBounds(long seconds) {
        assertThat(new EncodingRelayProperties(seconds).intervalSeconds()).isEqualTo(seconds);
    }
}
