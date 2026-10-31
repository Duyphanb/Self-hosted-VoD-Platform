package com.vodplatform.encoding.persistence;

import java.util.Locale;
import org.hibernate.exception.ConstraintViolationException;

/** Encoding-owned names used to distinguish persistence failures before API mapping. */
public final class EncodingConstraints {

    public static final String VIDEO_ASSETS_RAW_OBJECT = "ux_video_assets_raw_object";
    public static final String VIDEO_ASSETS_HLS_MASTER = "ux_video_assets_hls_master";
    public static final String VIDEO_ASSETS_MOVIE_IN_FLIGHT = "ux_video_assets_movie_in_flight";
    public static final String ENCODING_JOBS_ASSET_ATTEMPT = "ux_encoding_jobs_asset_attempt";

    private EncodingConstraints() {
    }

    /** Matches bare PostgreSQL names and H2's schema-qualified constraint/backing-index form. */
    public static boolean isViolationOf(Throwable failure, String constraintName) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ConstraintViolationException violation
                    && constraintName.equalsIgnoreCase(reportedName(violation.getConstraintName()))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String reportedName(String constraintName) {
        if (constraintName == null) {
            return null;
        }
        String name = constraintName.strip();
        for (int index = 0; index < name.length(); index++) {
            if (Character.isWhitespace(name.charAt(index))) {
                name = name.substring(0, index);
                break;
            }
        }
        return name.substring(name.lastIndexOf('.') + 1).replace("\"", "").toLowerCase(Locale.ROOT);
    }
}
