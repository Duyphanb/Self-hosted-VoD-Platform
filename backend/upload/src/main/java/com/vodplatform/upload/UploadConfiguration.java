package com.vodplatform.upload;

import jakarta.servlet.MultipartConfigElement;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Validates disk-backed multipart limits and prepares a dedicated spool before connector startup.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({UploadProperties.class, MultipartProperties.class})
public class UploadConfiguration {

    @Bean
    MultipartConfigElement multipartConfigElement(MultipartProperties properties) {
        if (properties.getMaxFileSize().toBytes() <= 0) {
            throw new IllegalArgumentException("Multipart file size must be positive");
        }
        if (!properties.getMaxFileSize().equals(properties.getMaxRequestSize())) {
            throw new IllegalArgumentException("Multipart file and request limits must be equal");
        }
        if (properties.getFileSizeThreshold().toBytes() != 0) {
            throw new IllegalArgumentException(
                    "Multipart threshold must be zero for disk spooling");
        }
        prepareSpool(properties.getLocation());
        return properties.createMultipartConfig();
    }

    /** Runs during dispatcher registration, before connector startup, not an ApplicationRunner. */
    public static void prepareSpool(String location) {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("Upload spool location must not be blank");
        }
        try {
            Path path = Path.of(location);
            if (!path.isAbsolute() || !path.normalize().equals(path) || path.getParent() == null) {
                throw new IllegalArgumentException(
                        "Upload spool must be an absolute normalized non-root path");
            }
            if (path.equals(
                            Path.of(System.getProperty("java.io.tmpdir"))
                                    .toAbsolutePath()
                                    .normalize())
                    || path.equals(
                            Path.of(System.getProperty("user.home")).toAbsolutePath().normalize())
                    || path.equals(Path.of("").toAbsolutePath().normalize())
                    || path.equals(Path.of("/tmp").toAbsolutePath())
                    || path.equals(Path.of("/var/tmp").toAbsolutePath())) {
                throw new IllegalArgumentException(
                        "Upload spool must be dedicated, not a shared directory");
            }
            for (Path part = path; part != null; part = part.getParent()) {
                if (Files.isSymbolicLink(part)) {
                    throw new IllegalArgumentException(
                            "Upload spool and ancestors must not be symbolic links");
                }
            }
            Files.createDirectories(path);
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || !Files.isWritable(path)) {
                throw new IllegalArgumentException("Upload spool must be a writable directory");
            }
            Instant start = Instant.now();
            try (var entries = Files.newDirectoryStream(path, "upload_*.tmp")) {
                for (Path entry : entries) {
                    if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                            && Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS)
                                    .toInstant()
                                    .isBefore(start)) {
                        Files.delete(entry);
                    }
                }
            }
        } catch (IOException | InvalidPathException failure) {
            throw new IllegalArgumentException(
                    "Cannot create or sweep the dedicated upload spool", failure);
        }
    }
}
