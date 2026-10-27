package com.vodplatform.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.json.JsonMapper;

class CanonicalUuidJacksonModuleTest {

    private static final String CANONICAL = "123e4567-e89b-12d3-a456-426614174000";

    private final JsonMapper mapper = JsonMapper.builder().addModule(new CanonicalUuidJacksonModule()).build();

    @ParameterizedTest
    @ValueSource(strings = {CANONICAL, "123E4567-E89B-12D3-A456-426614174000"})
    void readsTheCanonicalFormInEitherCase(String text) {
        assertThat(read("\"" + text + "\"")).isEqualTo(UUID.fromString(CANONICAL));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Jackson's own deserializer accepts these: padded text and the Base64 forms of the
            // 16 bytes (standard, URL-safe, URL-safe without padding).
            " " + CANONICAL + " ",
            "EjRWeJq83vAAERIjNEVWZw==",
            "/////////////////////w==",
            "_____________________w==",
            "_____________________w",
            // ... and reads whitespace-only text as null.
            "   ",
            // And rejects these, as before.
            "1-1-1-1-1",
            "123e4567e89b12d3a456426614174000",
            "{" + CANONICAL + "}",
            "not-a-uuid"
    })
    void rejectsEveryOtherStringAsAnInvalidFormat(String text) {
        assertThatExceptionOfType(InvalidFormatException.class)
                .isThrownBy(() -> read("\"" + text + "\""))
                .satisfies(exception -> assertThat(exception.getTargetType()).isEqualTo(UUID.class));
    }

    @Test
    void rejectsNumbers() {
        assertThatExceptionOfType(InvalidFormatException.class).isThrownBy(() -> read("123"));
    }

    @Test
    void keepsJacksonsDefaultsForNullAndTheEmptyString() {
        assertThat(read("null")).isNull();
        assertThat(read("\"\"")).isNull();
    }

    @Test
    void appliesToUuidsInsideCollections() {
        assertThat(mapper.readValue("{\"ids\": [\"" + CANONICAL + "\"]}", Ids.class).ids())
                .containsExactly(UUID.fromString(CANONICAL));
        assertThatExceptionOfType(InvalidFormatException.class)
                .isThrownBy(() -> mapper.readValue("{\"ids\": [\"EjRWeJq83vAAERIjNEVWZw==\"]}", Ids.class));
    }

    private UUID read(String json) {
        return mapper.readValue("{\"id\": " + json + "}", Body.class).id();
    }

    record Body(UUID id) {
    }

    record Ids(List<UUID> ids) {
    }
}
