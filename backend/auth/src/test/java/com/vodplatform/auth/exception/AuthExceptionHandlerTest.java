package com.vodplatform.auth.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.vodplatform.shared.error.ApiError;
import com.vodplatform.shared.error.ApiErrorFactory;
import com.vodplatform.shared.error.ApiExceptionHandler;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;

class AuthExceptionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-18T09:15:30Z");

    private final AuthExceptionHandler handler = new AuthExceptionHandler(
            new ApiErrorFactory(Clock.fixed(NOW, ZoneOffset.UTC))
    );

    @Test
    void mapsDuplicateEmailToConflict() {
        assertResponse(
                handler.handleDuplicateEmail(new EmailAlreadyExistsException()),
                HttpStatus.CONFLICT,
                "EMAIL_ALREADY_EXISTS",
                "Email is already registered"
        );
    }

    @Test
    void mapsInvalidCredentialsToUnauthorized() {
        assertResponse(
                handler.handleInvalidCredentials(new InvalidCredentialsException()),
                HttpStatus.UNAUTHORIZED,
                "INVALID_CREDENTIALS",
                "Invalid email or password"
        );
    }

    @Test
    void mapsInvalidRefreshTokenToUnauthorized() {
        assertResponse(
                handler.handleInvalidRefreshToken(new InvalidRefreshTokenException()),
                HttpStatus.UNAUTHORIZED,
                "INVALID_REFRESH_TOKEN",
                "Invalid refresh token"
        );
    }

    @Test
    void mapsUnavailableCurrentUserToUnauthorized() {
        assertResponse(
                handler.handleCurrentUserUnavailable(new CurrentUserUnavailableException()),
                HttpStatus.UNAUTHORIZED,
                "UNAUTHORIZED",
                "Authentication required or invalid token"
        );
    }

    @Test
    void handlesNoExceptionTypeOfTheSharedHandler() {
        List<Class<? extends Throwable>> authTypes = handledTypes(AuthExceptionHandler.class);
        List<Class<? extends Throwable>> sharedTypes = handledTypes(ApiExceptionHandler.class);

        assertThat(authTypes).containsExactlyInAnyOrder(
                EmailAlreadyExistsException.class,
                InvalidCredentialsException.class,
                InvalidRefreshTokenException.class,
                CurrentUserUnavailableException.class
        );
        assertThat(sharedTypes).isNotEmpty();
        for (Class<? extends Throwable> authType : authTypes) {
            for (Class<? extends Throwable> sharedType : sharedTypes) {
                assertThat(authType.isAssignableFrom(sharedType) || sharedType.isAssignableFrom(authType))
                        .as("%s overlaps %s", authType.getName(), sharedType.getName())
                        .isFalse();
            }
        }
    }

    private static void assertResponse(
            ResponseEntity<ApiError> response,
            HttpStatus status,
            String code,
            String message
    ) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isEqualTo(new ApiError(NOW, status.value(), code, message, List.of()));
    }

    private static List<Class<? extends Throwable>> handledTypes(Class<?> advice) {
        return Arrays.stream(advice.getDeclaredMethods())
                .map(method -> method.getAnnotation(ExceptionHandler.class))
                .filter(Objects::nonNull)
                .flatMap(annotation -> Arrays.stream(annotation.value()))
                .toList();
    }
}
