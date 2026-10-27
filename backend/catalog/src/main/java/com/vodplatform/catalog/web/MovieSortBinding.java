package com.vodplatform.catalog.web;

import com.vodplatform.catalog.persistence.MovieSort;
import java.beans.PropertyEditorSupport;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;

/**
 * Binds a {@link MovieSort} request parameter from its contract value ({@code latest},
 * {@code title}) only. Any other value, including the enum constant name such as {@code LATEST} or
 * a Spring Data sort expression such as {@code title,desc}, is a type mismatch, which the API
 * reports as 400 {@code VALIDATION_ERROR} for the parameter.
 *
 * <p>A property editor is used rather than a {@code Converter}: when a conversion-service
 * conversion fails, Spring's data binder falls back to looking up an enum constant by name, so a
 * converter alone would still accept {@code LATEST}. A registered editor replaces both.
 */
@ControllerAdvice
class MovieSortBinding {

    @InitBinder
    void registerMovieSortEditor(WebDataBinder binder) {
        binder.registerCustomEditor(MovieSort.class, new MovieSortEditor());
    }

    private static final class MovieSortEditor extends PropertyEditorSupport {

        @Override
        public void setAsText(String text) {
            setValue(MovieSort.fromContractValue(text)
                    .orElseThrow(() -> new IllegalArgumentException("Not a MovieSort contract value")));
        }
    }
}
