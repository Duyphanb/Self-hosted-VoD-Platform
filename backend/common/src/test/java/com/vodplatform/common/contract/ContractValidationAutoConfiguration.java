package com.vodplatform.common.contract;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;

/**
 * Adds {@link ContractValidation#matchesContract()} to every request of the auto-configured
 * {@code MockMvc}. It is listed in
 * {@code META-INF/spring/org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc.imports}
 * on this module's test classpath, so each {@code @AutoConfigureMockMvc} test imports it; being an
 * auto-configuration, the application's component scan skips it.
 */
@AutoConfiguration
public class ContractValidationAutoConfiguration {

    @Bean
    MockMvcBuilderCustomizer apiContractValidation() {
        return builder -> builder.alwaysExpect(ContractValidation.matchesContract());
    }
}
