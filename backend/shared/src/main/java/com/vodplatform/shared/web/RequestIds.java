package com.vodplatform.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Request correlation ids for operations that opt in to correlated error responses (the upload
 * operation, see {@code MultipartRouteFilter}). Other routes never resolve an id, so their error
 * bodies stay unchanged until global correlation (#139).
 *
 * <p>An inbound {@value #HEADER} value is reused only when it is a safe token; otherwise a random
 * UUID is generated. Ids are never echoed from unsafe input.
 */
public final class RequestIds {

    public static final String HEADER = "X-Request-ID";

    static final String ATTRIBUTE = RequestIds.class.getName() + ".requestId";

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private RequestIds() {
    }

    /** Returns the value when it is a safe correlation id, otherwise empty. */
    public static Optional<String> sanitize(String value) {
        return Optional.ofNullable(value).filter(candidate -> SAFE.matcher(candidate).matches());
    }

    /**
     * Resolves the id of a correlated request once and stores it on the request: the safe inbound
     * header, or a generated UUID. Later calls return the stored id.
     */
    public static String resolve(HttpServletRequest request) {
        if (request.getAttribute(ATTRIBUTE) instanceof String existing) {
            return existing;
        }
        String id = sanitize(request.getHeader(HEADER)).orElseGet(() -> UUID.randomUUID().toString());
        request.setAttribute(ATTRIBUTE, id);
        return id;
    }

    /** The id resolved for this request, or empty when the request is not correlated. */
    public static Optional<String> existing(HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof String id ? Optional.of(id) : Optional.empty();
    }

    /** The id resolved for the request bound to the current thread, if any. */
    public static Optional<String> current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return Optional.empty();
        }
        return attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) instanceof String id
                ? Optional.of(id)
                : Optional.empty();
    }
}
