package com.vodplatform.shared.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ApiExceptionTest {

    @Test
    void keepsStatusCodeMessageAndDefaultsToNoFieldErrors() {
        ApiException exception = new ApiException(HttpStatus.NOT_FOUND, "MOVIE_NOT_FOUND", "Movie not found");

        assertThat(exception).isInstanceOf(RuntimeException.class);
        assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exception.getCode()).isEqualTo("MOVIE_NOT_FOUND");
        assertThat(exception.getMessage()).isEqualTo("Movie not found");
        assertThat(exception.getFieldErrors()).isEmpty();
    }

    @Test
    void copiesFieldErrorsDefensively() {
        List<ApiFieldError> fieldErrors = new ArrayList<>(List.of(new ApiFieldError("slug", "must be unique")));

        ApiException exception = new ApiException(
                HttpStatus.CONFLICT,
                "MOVIE_SLUG_CONFLICT",
                "Movie slug already exists",
                fieldErrors
        );
        fieldErrors.add(new ApiFieldError("title", "must not be blank"));

        assertThat(exception.getFieldErrors()).containsExactly(new ApiFieldError("slug", "must be unique"));
        assertThat(new ApiException(HttpStatus.CONFLICT, "CODE", "message", null).getFieldErrors()).isEmpty();
    }

    @Test
    void requiresStatusCodeAndMessage() {
        assertThatNullPointerException().isThrownBy(() -> new ApiException(null, "CODE", "message"));
        assertThatNullPointerException().isThrownBy(() -> new ApiException(HttpStatus.CONFLICT, null, "message"));
        assertThatNullPointerException().isThrownBy(() -> new ApiException(HttpStatus.CONFLICT, "CODE", null));
    }
}
