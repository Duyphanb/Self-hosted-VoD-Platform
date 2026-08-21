package com.vodplatform.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vodplatform.auth.exception.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private static final String ERROR_CODE = "FORBIDDEN";
    private static final String ERROR_MESSAGE = "Authenticated user is not allowed to perform this action";

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ApiAccessDeniedHandler(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                new ApiError(
                        clock.instant(),
                        HttpStatus.FORBIDDEN.value(),
                        ERROR_CODE,
                        ERROR_MESSAGE,
                        List.of()
                )
        );
    }
}
