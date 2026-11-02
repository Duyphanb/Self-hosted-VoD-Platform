package com.vodplatform.auth.security;

import com.vodplatform.shared.error.ApiErrorFactory;
import com.vodplatform.shared.web.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes the 401 {@code ApiError}. A correlated request (the upload operation) also carries its
 * {@code requestId}; other routes keep the uncorrelated body.
 */
@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String ERROR_CODE = "UNAUTHORIZED";
    private static final String ERROR_MESSAGE = "Authentication required or invalid token";

    private final ObjectMapper objectMapper;
    private final ApiErrorFactory apiErrorFactory;

    public ApiAuthenticationEntryPoint(ObjectMapper objectMapper, ApiErrorFactory apiErrorFactory) {
        this.objectMapper = objectMapper;
        this.apiErrorFactory = apiErrorFactory;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authenticationException
    ) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                apiErrorFactory.create(
                        HttpStatus.UNAUTHORIZED,
                        ERROR_CODE,
                        ERROR_MESSAGE,
                        List.of(),
                        RequestIds.existing(request).orElse(null)
                )
        );
    }
}
