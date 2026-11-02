package com.vodplatform.common.web;

import com.vodplatform.shared.error.ApiError;
import com.vodplatform.shared.error.ApiErrorFactory;
import com.vodplatform.shared.web.RequestIds;

import jakarta.servlet.RequestDispatcher;

import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Error-page body for correlated requests (the upload operation, see {@link MultipartRouteFilter}).
 * When the container itself answers such a request, for example {@code sendError(400)} after a
 * malformed chunked body is read, no handler can write the operation's {@code ApiError}; this keeps
 * the contract's {@code ApiError} with the operation's {@code requestId} for 400, 413 and 500.
 * Every other error page keeps Spring Boot's default attributes.
 */
@Component
public class CorrelatedErrorAttributes extends DefaultErrorAttributes {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};

    private final ApiErrorFactory errors;

    private final ObjectMapper mapper;

    public CorrelatedErrorAttributes(ApiErrorFactory errors, ObjectMapper mapper) {
        this.errors = errors;
        this.mapper = mapper;
    }

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
        Optional<String> requestId =
                webRequest instanceof ServletWebRequest servlet
                        ? RequestIds.existing(servlet.getRequest())
                        : Optional.empty();
        Object code =
                webRequest.getAttribute(RequestDispatcher.ERROR_STATUS_CODE, RequestAttributes.SCOPE_REQUEST);
        HttpStatus status = code instanceof Integer value ? HttpStatus.resolve(value) : null;
        if (requestId.isEmpty() || status == null) {
            return super.getErrorAttributes(webRequest, options);
        }
        // By value: 413's enum constant is CONTENT_TOO_LARGE in Spring 7 (PAYLOAD_TOO_LARGE is an alias).
        ApiError error =
                switch (status.value()) {
                    case 400 ->
                            errors.create(
                                    status,
                                    "MALFORMED_REQUEST",
                                    "Malformed multipart request",
                                    List.of(),
                                    requestId.get());
                    case 413 ->
                            errors.create(
                                    status,
                                    "PAYLOAD_TOO_LARGE",
                                    "File exceeds upload size limit",
                                    List.of(),
                                    requestId.get());
                    case 500 ->
                            errors.create(
                                    status,
                                    "INTERNAL_ERROR",
                                    "Unexpected server error; the upload result is unknown",
                                    List.of(),
                                    requestId.get());
                    default -> null;
                };
        return error == null ? super.getErrorAttributes(webRequest, options) : mapper.convertValue(error, MAP);
    }
}
