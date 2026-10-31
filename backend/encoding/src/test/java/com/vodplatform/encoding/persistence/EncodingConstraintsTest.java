package com.vodplatform.encoding.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class EncodingConstraintsTest {

    @Test
    void matchesTheBarePostgresqlIndexNameThroughTheCauseChain() {
        Throwable failure = new IllegalStateException(new DataIntegrityViolationException("duplicate",
                violation("ux_video_assets_movie_in_flight")));

        assertThat(EncodingConstraints.isViolationOf(failure, EncodingConstraints.VIDEO_ASSETS_MOVIE_IN_FLIGHT)).isTrue();
        assertThat(EncodingConstraints.isViolationOf(failure, EncodingConstraints.VIDEO_ASSETS_RAW_OBJECT)).isFalse();
    }

    @Test
    void matchesTheQualifiedCaseInsensitiveH2Name() {
        Throwable failure = violation("PUBLIC.UX_VIDEO_ASSETS_RAW_OBJECT INDEX PUBLIC.UX_VIDEO_ASSETS_RAW_OBJECT_INDEX_7");

        assertThat(EncodingConstraints.isViolationOf(failure, EncodingConstraints.VIDEO_ASSETS_RAW_OBJECT)).isTrue();
        assertThat(EncodingConstraints.isViolationOf(failure, EncodingConstraints.VIDEO_ASSETS_MOVIE_IN_FLIGHT)).isFalse();
    }

    @Test
    void matchesAQuotedSchemaQualifiedName() {
        assertThat(EncodingConstraints.isViolationOf(violation("\"public\".\"ux_encoding_jobs_asset_attempt\""),
                EncodingConstraints.ENCODING_JOBS_ASSET_ATTEMPT)).isTrue();
    }

    @Test
    void rejectsPrefixesMessagesAndUnknownNames() {
        assertThat(EncodingConstraints.isViolationOf(violation("ux_video_assets_movie_in_flight_old"),
                EncodingConstraints.VIDEO_ASSETS_MOVIE_IN_FLIGHT)).isFalse();
        assertThat(EncodingConstraints.isViolationOf(violation(null), EncodingConstraints.VIDEO_ASSETS_MOVIE_IN_FLIGHT)).isFalse();
        assertThat(EncodingConstraints.isViolationOf(new IllegalStateException("ux_video_assets_movie_in_flight"),
                EncodingConstraints.VIDEO_ASSETS_MOVIE_IN_FLIGHT)).isFalse();
        assertThat(EncodingConstraints.isViolationOf(null, EncodingConstraints.VIDEO_ASSETS_MOVIE_IN_FLIGHT)).isFalse();
    }

    private static ConstraintViolationException violation(String name) {
        return new ConstraintViolationException("duplicate", new SQLException("duplicate"), name);
    }
}
