package com.vodplatform.common;

import static com.vodplatform.common.contract.ContractValidation.offContract;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Real storage contributor and HTTP health aggregation, with only local H2/closed-port fixtures. */
@SpringBootTest(properties = "auth.tokens.secret=test-only-secret-with-at-least-32-bytes")
@AutoConfigureMockMvc
class StorageReadinessIntegrationTests {
    private static final int CLOSED_PORT = closedPort();

    private final MockMvc mvc;

    @Autowired
    StorageReadinessIntegrationTests(MockMvc mvc) {
        this.mvc = mvc;
    }

    @DynamicPropertySource
    static void storageUnavailable(DynamicPropertyRegistry registry) {
        registry.add("storage.endpoint", () -> "http://127.0.0.1:" + CLOSED_PORT);
        registry.add("storage.connect-timeout-ms", () -> 200);
        registry.add("storage.read-timeout-ms", () -> 200);
        registry.add("storage.write-timeout-ms", () -> 200);
    }

    @Test
    void realStorageFailureMakesActuatorUnavailableWithoutExposingDetails() throws Exception {
        mvc.perform(get("/actuator/health").with(offContract(
                        "Actuator health is the deployment readiness signal outside the public API contract")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    private static int closedPort() {
        try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException failure) {
            throw new IllegalStateException("Could not reserve the local storage test port");
        }
    }
}
