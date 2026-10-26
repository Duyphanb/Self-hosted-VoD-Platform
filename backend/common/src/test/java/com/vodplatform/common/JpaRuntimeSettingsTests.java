package com.vodplatform.common;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;

/**
 * The application disables open-session-in-view and batches lazy loads. The interceptor only
 * exists in a web context, so this uses the same web context configuration as the auth
 * integration tests.
 */
@SpringBootTest(
        properties = {
                "auth.tokens.secret=test-only-secret-with-at-least-32-bytes",
                "auth.tokens.access-token-ttl=15m",
                "auth.tokens.refresh-token-ttl=7d"
        }
)
@AutoConfigureMockMvc
class JpaRuntimeSettingsTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void openSessionInViewIsDisabled() {
        assertThat(applicationContext.getBeanNamesForType(OpenEntityManagerInViewInterceptor.class)).isEmpty();
    }

    @Test
    void lazyLoadsAreBatched() {
        assertThat(entityManagerFactory.getProperties()).containsEntry("hibernate.default_batch_fetch_size", "50");
    }
}
