package com.vodplatform.auth.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "security.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        if (allowedOrigins == null
                || allowedOrigins.isEmpty()
                || (allowedOrigins.size() == 1
                        && allowedOrigins.getFirst() != null
                        && allowedOrigins.getFirst().isBlank())) {
            allowedOrigins = List.of();
        } else {
            Set<String> normalizedOrigins = new LinkedHashSet<>();
            for (String candidate : allowedOrigins) {
                String normalizedOrigin = normalizeOrigin(candidate);
                if (!normalizedOrigins.add(normalizedOrigin)) {
                    throw new IllegalArgumentException(
                            "CORS allowed origins must not contain canonical duplicates"
                    );
                }
            }
            allowedOrigins = List.copyOf(normalizedOrigins);
        }
    }

    private static String normalizeOrigin(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalArgumentException("CORS allowed origins must not contain blank entries");
        }

        URI origin;
        try {
            origin = new URI(candidate.trim());
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("CORS allowed origin is not a valid URI", exception);
        }

        String scheme = origin.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("CORS allowed origin must use http or https");
        }
        if (origin.isOpaque()
                || origin.getHost() == null
                || origin.getRawUserInfo() != null
                || (origin.getRawPath() != null && !origin.getRawPath().isEmpty())
                || origin.getRawQuery() != null
                || origin.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "CORS allowed origin must contain only scheme, host, and optional port"
            );
        }

        int port = origin.getPort();
        if (port == 0 || port > 65_535) {
            throw new IllegalArgumentException("CORS allowed origin contains an invalid port");
        }

        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        String normalizedHost = origin.getHost().toLowerCase(Locale.ROOT);
        if (normalizedHost.contains(":") && !normalizedHost.startsWith("[")) {
            normalizedHost = "[" + normalizedHost + "]";
        }
        int normalizedPort = isDefaultPort(normalizedScheme, port) ? -1 : port;

        return normalizedScheme
                + "://"
                + normalizedHost
                + (normalizedPort == -1 ? "" : ":" + normalizedPort);
    }

    private static boolean isDefaultPort(String scheme, int port) {
        return (scheme.equals("http") && port == 80)
                || (scheme.equals("https") && port == 443);
    }
}
