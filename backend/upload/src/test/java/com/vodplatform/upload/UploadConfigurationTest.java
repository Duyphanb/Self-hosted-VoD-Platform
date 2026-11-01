package com.vodplatform.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.util.unit.DataSize;

import java.nio.file.Files;
import java.nio.file.Path;

class UploadConfigurationTest {

    @TempDir Path temp;

    private MultipartProperties properties() {
        var properties = new MultipartProperties();
        properties.setLocation(temp.resolve("spool").toString());
        properties.setMaxFileSize(DataSize.ofMegabytes(2048));
        properties.setMaxRequestSize(DataSize.ofMegabytes(2048));
        properties.setFileSizeThreshold(DataSize.ofBytes(0));
        return properties;
    }

    @Test
    void createsExpectedDefaultMultipartElement() {
        var properties = properties();
        var config = new UploadConfiguration().multipartConfigElement(properties);
        assertThat(config.getLocation()).isEqualTo(properties.getLocation());
        assertThat(config.getMaxFileSize()).isEqualTo(2048L * 1024 * 1024);
        assertThat(config.getMaxRequestSize()).isEqualTo(config.getMaxFileSize());
        assertThat(config.getFileSizeThreshold()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"unequal", "threshold", "zero", "negative"})
    void rejectsUnsafeLimitsBeforePreparingSpool(String rule) {
        var properties = properties();
        switch (rule) {
            case "unequal" -> properties.setMaxRequestSize(DataSize.ofMegabytes(2049));
            case "threshold" -> properties.setFileSizeThreshold(DataSize.ofBytes(1));
            case "zero" -> {
                properties.setMaxFileSize(DataSize.ofBytes(0));
                properties.setMaxRequestSize(DataSize.ofBytes(0));
            }
            case "negative" -> {
                properties.setMaxFileSize(DataSize.ofBytes(-1));
                properties.setMaxRequestSize(DataSize.ofBytes(-1));
            }
            default -> throw new AssertionError(rule);
        }
        assertThatThrownBy(() -> new UploadConfiguration().multipartConfigElement(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        switch (rule) {
                            case "zero", "negative" -> "Multipart file size must be positive";
                            case "unequal" -> "Multipart file and request limits must be equal";
                            case "threshold" ->
                                    "Multipart threshold must be zero for disk spooling";
                            default -> throw new AssertionError(rule);
                        });
        assertThat(Path.of(properties.getLocation())).doesNotExist();
    }

    @Test
    void spoolIoFailureNamesOperationAndRetainsCause() throws Exception {
        Path file = Files.writeString(temp.resolve("file"), "fixture");
        assertThatThrownBy(() -> UploadConfiguration.prepareSpool(file.resolve("spool").toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cannot create or sweep the dedicated upload spool")
                .hasCauseInstanceOf(java.io.IOException.class);
    }
}
