package com.vodplatform.shared.web;

import java.util.UUID;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;

/**
 * Binds every {@link UUID} path variable, request parameter and header of every controller with
 * {@link CanonicalUuidEditor}, so only the canonical 8-4-4-4-12 form is accepted. Any other value,
 * such as {@code 1-1-1-1-1}, is a type mismatch, which {@code ApiExceptionHandler} reports as 400
 * {@code VALIDATION_ERROR} "must be a valid UUID" for the parameter, without the value.
 *
 * <p>A property editor is used rather than a {@code Converter}: a registered editor takes
 * precedence over the conversion service, whose default UUID conversion trims the text and then
 * accepts whatever {@link UUID#fromString(String)} accepts. UUIDs in JSON request bodies are read
 * by Jackson instead; see {@link CanonicalUuidJacksonModule}.
 */
@ControllerAdvice
class CanonicalUuidBinding {

    @InitBinder
    void registerCanonicalUuidEditor(WebDataBinder binder) {
        binder.registerCustomEditor(UUID.class, new CanonicalUuidEditor());
    }
}
