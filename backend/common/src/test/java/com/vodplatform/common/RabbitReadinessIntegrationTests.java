package com.vodplatform.common;

import static com.vodplatform.common.contract.ContractValidation.offContract;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.vodplatform.common.storage.StorageHealthIndicator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Real Rabbit contributor at the test-only closed loopback port; storage is healthy. */
@SpringBootTest(properties = "auth.tokens.secret=test-only-secret-with-at-least-32-bytes")
@AutoConfigureMockMvc
class RabbitReadinessIntegrationTests {
    @Autowired MockMvc mvc;
    @MockitoBean StorageHealthIndicator storageHealthIndicator;

    @Test
    void realRabbitFailureAloneMakesHealthUnavailableWithoutDetails() throws Exception {
        when(storageHealthIndicator.health()).thenReturn(Health.up().build());
        mvc.perform(get("/actuator/health").with(offContract("Deployment health is outside the public API contract")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }
}
