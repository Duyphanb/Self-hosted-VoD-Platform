package com.vodplatform.shared.web;

import java.beans.PropertyEditorSupport;

/**
 * Converts request text to a {@link java.util.UUID} in canonical form only (see
 * {@link CanonicalUuid}). Any other text is an {@link IllegalArgumentException}, which Spring
 * reports as a type mismatch for the parameter.
 *
 * <p>Empty text has no value, as with Spring's own UUID conversion, so an empty optional query
 * parameter counts as absent; a path variable is never empty. Whitespace-only or padded text is
 * rejected, not trimmed.
 */
final class CanonicalUuidEditor extends PropertyEditorSupport {

    @Override
    public void setAsText(String text) {
        setValue(text == null || text.isEmpty() ? null : CanonicalUuid.parse(text));
    }

    @Override
    public String getAsText() {
        Object value = getValue();
        return value == null ? "" : value.toString();
    }
}
