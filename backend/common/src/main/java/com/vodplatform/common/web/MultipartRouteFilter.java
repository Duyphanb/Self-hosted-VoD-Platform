package com.vodplatform.common.web;

import com.vodplatform.shared.error.ApiErrorFactory;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Rejects multipart outside the upload operation before any parameter reader can trigger Tomcat's
 * multipart parser. This filter never reads parameters, parts or the body. The allowed operation
 * still passes through the production security chain before Spring's lazy multipart resolution.
 */
public final class MultipartRouteFilter extends OncePerRequestFilter {

    private static final RequestMatcher UPLOAD =
            PathPatternRequestMatcher.pathPattern(
                    HttpMethod.POST, "/api/v1/admin/movies/{movieId}/video");

    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    // This gate runs before FilterChainProxy: preserve its default strict URL rules when deciding
    // the sole exception. In particular, PathPattern alone ignores semicolon path parameters.
    private final StrictHttpFirewall firewall = new StrictHttpFirewall();

    private final ApiErrorFactory errors;

    private final ObjectMapper mapper;

    public MultipartRouteFilter(ApiErrorFactory errors, ObjectMapper mapper) {
        this.errors = errors;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isMultipart(request.getContentType()) || isUpload(request)) {
            chain.doFilter(request, response);
            return;
        }
        String requestId = request.getHeader("X-Request-ID");
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            requestId = UUID.randomUUID().toString();
        }
        ObjectNode body =
                mapper.valueToTree(
                        errors.create(
                                HttpStatus.BAD_REQUEST,
                                "MALFORMED_REQUEST",
                                "Multipart body is not allowed on this route",
                                List.of()));
        body.put("requestId", requestId);
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), body);
    }

    private static boolean isMultipart(String contentType) {
        if (contentType == null) {
            return false;
        }
        String mediaType = contentType.split(";", 2)[0].trim();
        return mediaType.regionMatches(true, 0, "multipart/", 0, "multipart/".length());
    }

    private boolean isUpload(HttpServletRequest request) {
        try {
            var checked = firewall.getFirewalledRequest(request);
            try {
                return UPLOAD.matches(checked);
            } finally {
                checked.reset();
            }
        } catch (RequestRejectedException | IllegalArgumentException invalidPath) {
            return false;
        }
    }
}
