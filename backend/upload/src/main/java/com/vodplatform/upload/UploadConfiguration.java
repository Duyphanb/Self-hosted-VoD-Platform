package com.vodplatform.upload;

import jakarta.servlet.MultipartConfigElement;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties({UploadProperties.class, MultipartProperties.class})
public class UploadConfiguration {
    @Bean
    MultipartConfigElement multipartConfigElement(MultipartProperties properties) {
        if (properties.getMaxFileSize().toBytes()<=0 || !properties.getMaxFileSize().equals(properties.getMaxRequestSize())
                || properties.getFileSizeThreshold().toBytes()!=0) throw new IllegalArgumentException("Unsafe multipart limits");
        prepareSpool(properties.getLocation());
        return properties.createMultipartConfig();
    }
    /** Runs during dispatcher registration, before connector startup, not an ApplicationRunner. */
    public static void prepareSpool(String location) {
        if (location==null || location.isBlank()) throw new IllegalArgumentException("Unsafe upload spool");
        try {
            Path path=Path.of(location);
            if (!path.isAbsolute() || !path.normalize().equals(path) || path.getParent()==null
                    || path.equals(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())
                    || path.equals(Path.of(System.getProperty("user.home")).toAbsolutePath().normalize())
                    || path.equals(Path.of("").toAbsolutePath().normalize())
                    || path.equals(Path.of("/tmp").toAbsolutePath()) || path.equals(Path.of("/var/tmp").toAbsolutePath()))
                throw new IllegalArgumentException("Unsafe upload spool");
            for (Path part=path; part!=null; part=part.getParent()) if (Files.isSymbolicLink(part)) throw new IllegalArgumentException("Unsafe upload spool");
            Files.createDirectories(path);
            if (!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS) || !Files.isWritable(path)) throw new IllegalArgumentException("Unsafe upload spool");
            Instant start=Instant.now();
            try (var entries=Files.newDirectoryStream(path,"upload_*.tmp")) {
                for (Path entry:entries) if (Files.isRegularFile(entry,LinkOption.NOFOLLOW_LINKS)
                        && Files.getLastModifiedTime(entry,LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(start)) Files.delete(entry);
            }
        } catch (IOException | InvalidPathException failure) { throw new IllegalArgumentException("Cannot prepare upload spool"); }
    }
}
