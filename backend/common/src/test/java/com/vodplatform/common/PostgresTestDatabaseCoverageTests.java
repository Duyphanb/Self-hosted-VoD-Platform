package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.vodplatform.common.support.PostgresTestDatabase;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

/**
 * Issue #119: every Spring context test of this module runs on the test PostgreSQL, so none falls
 * back to whatever database the environment configures. Needs no Docker.
 *
 * <p>A subclass of a context test declares the interface again: JUnit reads the
 * {@code -DskipPostgresTests} condition only from the interfaces a class implements directly.
 */
class PostgresTestDatabaseCoverageTests {

    @Test
    void everySpringBootTestImplementsPostgresTestDatabaseDirectly() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return definition.getMetadata().isIndependent();
            }
        };
        scanner.addIncludeFilter(new AnnotationTypeFilter(SpringBootTest.class));
        List<Class<?>> contextTests = scanner.findCandidateComponents("com.vodplatform").stream()
                .map(BeanDefinition::getBeanClassName)
                .<Class<?>>map(name -> ClassUtils.resolveClassName(name, getClass().getClassLoader()))
                .toList();

        assertThat(contextTests).as("the scan finds the context tests")
                .contains(LoginIntegrationTests.class, UploadPostgresTests.class);
        assertThat(contextTests)
                .filteredOn(test -> !Arrays.asList(test.getInterfaces()).contains(PostgresTestDatabase.class))
                .as("@SpringBootTest classes that do not implement PostgresTestDatabase directly")
                .isEmpty();
    }
}
