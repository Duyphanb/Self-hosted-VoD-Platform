package com.vodplatform.upload;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("upload")
public record UploadProperties(String rawBucket, Set<String> allowedMimeTypes) {
    public static final Set<String> TYPES=Set.of("video/mp4","video/quicktime","video/x-matroska","video/webm");
    public UploadProperties {
        if (rawBucket==null || !rawBucket.matches("[a-z0-9][a-z0-9.-]{1,98}[a-z0-9]")) throw new IllegalArgumentException("Invalid raw bucket configuration");
        if (allowedMimeTypes==null || allowedMimeTypes.isEmpty() || !TYPES.containsAll(allowedMimeTypes)) throw new IllegalArgumentException("Invalid upload MIME allowlist");
        allowedMimeTypes=Set.copyOf(allowedMimeTypes);
    }
}
