package com.vodplatform.shared.web;

import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.jdk.UUIDDeserializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * Reads every {@link UUID} in a JSON request body in canonical form only (see
 * {@link CanonicalUuid}). Spring Boot registers this module bean with the application's
 * {@code JsonMapper}, which Spring MVC uses for request bodies.
 *
 * <p>Jackson's own UUID deserializer already rejects {@code 1-1-1-1-1}, but it trims surrounding
 * whitespace (reading whitespace-only text as {@code null}) and also accepts the Base64 forms of
 * the 16 bytes, standard or URL-safe, with or without padding. Any other text is now an invalid
 * value, which {@code ApiExceptionHandler} reports as 400 {@code MALFORMED_REQUEST}, as before for
 * text that is not a UUID at all. JSON {@code null} and the empty string keep Jackson's defaults
 * (both read as {@code null}).
 */
@Component
class CanonicalUuidJacksonModule extends SimpleModule {

    CanonicalUuidJacksonModule() {
        super(CanonicalUuidJacksonModule.class.getSimpleName());
        addDeserializer(UUID.class, new CanonicalUuidDeserializer());
    }

    static final class CanonicalUuidDeserializer extends UUIDDeserializer {

        /** Keeps surrounding whitespace, so padded text is rejected instead of trimmed. */
        @Override
        protected boolean _shouldTrim() {
            return false;
        }

        @Override
        protected UUID _deserialize(String id, DeserializationContext context) throws JacksonException {
            if (!CanonicalUuid.isCanonical(id)) {
                return (UUID) context.handleWeirdStringValue(UUID.class, id,
                        "not a UUID in canonical 8-4-4-4-12 form");
            }
            return UUID.fromString(id);
        }
    }
}
