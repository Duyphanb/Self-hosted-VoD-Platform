package com.vodplatform.auth.web;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vodplatform.auth.exception.AuthExceptionHandler;
import com.vodplatform.auth.service.LogoutService;
import com.vodplatform.auth.service.RefreshTokenRotationService;
import com.vodplatform.shared.error.ApiErrorFactory;
import com.vodplatform.shared.error.ApiExceptionHandler;
import java.time.Clock;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@ExtendWith(MockitoExtension.class)
class RefreshAndLogoutControllerTest {

    @Mock
    private RefreshTokenRotationService refreshTokenRotationService;

    @Mock
    private LogoutService logoutService;

    private LocalValidatorFactoryBean validator;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        ApiErrorFactory apiErrorFactory = new ApiErrorFactory(Clock.systemUTC());
        mockMvc = MockMvcBuilders
                .standaloneSetup(
                        new RefreshTokenController(refreshTokenRotationService),
                        new LogoutController(logoutService)
                )
                .setControllerAdvice(
                        new AuthExceptionHandler(apiErrorFactory),
                        new ApiExceptionHandler(apiErrorFactory)
                )
                .setValidator(validator)
                .build();
    }

    @AfterEach
    void closeValidatorFactory() {
        validator.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/auth/refresh", "/api/v1/auth/logout"})
    void rejectsOversizedRefreshTokenBeforeHashingOrLookup(String path) throws Exception {
        String oversizedToken = "t".repeat(129);

        mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"" + oversizedToken + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(content().string(Matchers.not(Matchers.containsString(oversizedToken))));
        verifyNoInteractions(refreshTokenRotationService, logoutService);
    }
}
