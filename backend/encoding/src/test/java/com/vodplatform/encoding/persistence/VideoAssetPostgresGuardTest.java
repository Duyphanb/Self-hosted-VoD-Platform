package com.vodplatform.encoding.persistence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class VideoAssetPostgresGuardTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:postgresql://127.0.0.1:5432/vod_upload_test",
                "jdbc:postgresql://localhost:5555/vod_upload_test"
            })
    void acceptsOnlyExplicitLoopbackTestDatabase(String url) {
        assertThatCode(() -> VideoAssetPostgresTest.requireDisposableUrl(url))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
            strings = {
                "jdbc:postgresql://localhost:5432/vod_platform",
                "jdbc:postgresql://localhost:5432/vod",
                "jdbc:postgresql://localhost:5432/vod_upload_test_copy",
                "jdbc:postgresql://example.com:5432/vod_upload_test",
                "jdbc:postgresql://localhost:5432/vod_upload_test?currentSchema=public"
            })
    void refusesApplicationOrAmbiguousDatabaseTargets(String url) {
        assertThatThrownBy(() -> VideoAssetPostgresTest.requireDisposableUrl(url))
                .isInstanceOf(IllegalStateException.class);
    }
}
