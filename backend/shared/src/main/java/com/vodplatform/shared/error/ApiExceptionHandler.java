package com.vodplatform.shared.error;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Generic API error mappings shared by every feature module. Feature-specific exception types
 * are handled by their own module advice; no exception type may be handled by two advices.
 *
 * <p>Validation responses never echo rejected values or exception details: field names come
 * from the request binding or the property path, and messages are either the constraint message
 * or a fixed text.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final String VALIDATION_ERROR_CODE = "VALIDATION_ERROR";
    private static final String VALIDATION_ERROR_MESSAGE = "Request validation failed";
    private static final String INVALID_VALUE_MESSAGE = "has an invalid value";
    private static final Comparator<ApiFieldError> FIELD_ERROR_ORDER = Comparator
            .comparing(ApiFieldError::field)
            .thenComparing(ApiFieldError::message);

    private final ApiErrorFactory apiErrorFactory;

    public ApiExceptionHandler(ApiErrorFactory apiErrorFactory) {
        this.apiErrorFactory = apiErrorFactory;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
        List<ApiFieldError> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiFieldError(error.getField(), fieldErrorMessage(error)))
                .sorted(FIELD_ERROR_ORDER)
                .toList();

        return validationError(fieldErrors);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        String field = StringUtils.hasText(exception.getName()) ? exception.getName() : "parameter";
        return validationError(List.of(new ApiFieldError(field, typeMismatchMessage(exception.getRequiredType()))));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> handleMethodValidation(HandlerMethodValidationException exception) {
        if (exception.isForReturnValue()) {
            // A return value failure is a server defect, not a client error. Rethrowing the
            // original exception hands it back to Spring's default resolution.
            throw exception;
        }

        List<ApiFieldError> fieldErrors = new ArrayList<>();
        for (ParameterValidationResult result : exception.getParameterValidationResults()) {
            if (result instanceof ParameterErrors errors) {
                for (FieldError error : errors.getFieldErrors()) {
                    fieldErrors.add(new ApiFieldError(error.getField(), fieldErrorMessage(error)));
                }
            } else {
                String field = bindingName(result.getMethodParameter()) + indexSuffix(result.getContainerIndex());
                for (MessageSourceResolvable error : result.getResolvableErrors()) {
                    fieldErrors.add(new ApiFieldError(field, constraintMessage(error.getDefaultMessage())));
                }
            }
        }

        return validationError(fieldErrors.stream().sorted(FIELD_ERROR_ORDER).toList());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException exception) {
        Set<ConstraintViolation<?>> violations = exception.getConstraintViolations() == null
                ? Set.of()
                : exception.getConstraintViolations();
        if (violations.stream().anyMatch(ApiExceptionHandler::isReturnValueViolation)) {
            // Same as a return value failure above: not the client's fault, so keep the default.
            throw exception;
        }

        List<ApiFieldError> fieldErrors = violations.stream()
                .map(violation -> new ApiFieldError(
                        violationField(violation.getPropertyPath()),
                        constraintMessage(violation.getMessage())
                ))
                .filter(error -> !error.field().isEmpty())
                .sorted(FIELD_ERROR_ORDER)
                .toList();

        return validationError(fieldErrors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadableRequest() {
        return apiErrorFactory.response(
                HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST",
                "Request body is missing or malformed",
                List.of()
        );
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApiException(ApiException exception) {
        return apiErrorFactory.response(
                exception.getStatus(),
                exception.getCode(),
                exception.getMessage(),
                exception.getFieldErrors()
        );
    }

    private ResponseEntity<ApiError> validationError(List<ApiFieldError> fieldErrors) {
        return apiErrorFactory.response(
                HttpStatus.BAD_REQUEST,
                VALIDATION_ERROR_CODE,
                VALIDATION_ERROR_MESSAGE,
                fieldErrors
        );
    }

    /**
     * A binding failure (for example text bound to a numeric property) carries a default message
     * that quotes the rejected value and the conversion error, so a fixed text replaces it.
     */
    private static String fieldErrorMessage(FieldError error) {
        return error.isBindingFailure() ? INVALID_VALUE_MESSAGE : constraintMessage(error.getDefaultMessage());
    }

    private static String constraintMessage(String message) {
        return StringUtils.hasText(message) ? message : INVALID_VALUE_MESSAGE;
    }

    private static String typeMismatchMessage(Class<?> requiredType) {
        if (requiredType == null) {
            return INVALID_VALUE_MESSAGE;
        }
        Class<?> type = ClassUtils.resolvePrimitiveIfNecessary(requiredType);
        if (UUID.class.equals(type)) {
            return "must be a valid UUID";
        }
        if (type.isEnum()) {
            return "is not an allowed value";
        }
        if (Integer.class.equals(type) || Long.class.equals(type) || Short.class.equals(type)
                || Byte.class.equals(type)) {
            return "must be an integer";
        }
        if (Number.class.isAssignableFrom(type)) {
            return "must be a number";
        }
        if (Boolean.class.equals(type)) {
            return "must be true or false";
        }
        return INVALID_VALUE_MESSAGE;
    }

    /**
     * Returns the name the client used for the parameter: the explicit name of a request
     * parameter, path variable, header or part, otherwise the Java parameter name.
     */
    private static String bindingName(MethodParameter parameter) {
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        if (requestParam != null) {
            return explicitOrParameterName(requestParam.name(), requestParam.value(), parameter);
        }
        PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
        if (pathVariable != null) {
            return explicitOrParameterName(pathVariable.name(), pathVariable.value(), parameter);
        }
        RequestHeader requestHeader = parameter.getParameterAnnotation(RequestHeader.class);
        if (requestHeader != null) {
            return explicitOrParameterName(requestHeader.name(), requestHeader.value(), parameter);
        }
        RequestPart requestPart = parameter.getParameterAnnotation(RequestPart.class);
        if (requestPart != null) {
            return explicitOrParameterName(requestPart.name(), requestPart.value(), parameter);
        }
        return parameterName(parameter);
    }

    private static String explicitOrParameterName(String name, String value, MethodParameter parameter) {
        if (StringUtils.hasText(name)) {
            return name;
        }
        return StringUtils.hasText(value) ? value : parameterName(parameter);
    }

    private static String parameterName(MethodParameter parameter) {
        String name = parameter.getParameterName();
        return name != null ? name : "arg" + parameter.getParameterIndex();
    }

    private static String indexSuffix(Integer index) {
        return index == null ? "" : "[" + index + "]";
    }

    private static boolean isReturnValueViolation(ConstraintViolation<?> violation) {
        if (violation.getPropertyPath() == null) {
            return false;
        }
        for (Path.Node node : violation.getPropertyPath()) {
            if (node.getKind() == ElementKind.RETURN_VALUE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the field from the parameter and property nodes of a violation path, so method
     * validation paths such as {@code create.request.title} become {@code request.title}. Element
     * indexes are kept; map keys are request data and are left out.
     */
    private static String violationField(Path path) {
        if (path == null) {
            return "";
        }
        StringBuilder field = new StringBuilder();
        for (Path.Node node : path) {
            if (node.isInIterable() && node.getIndex() != null && !field.isEmpty()) {
                field.append(indexSuffix(node.getIndex()));
            }
            ElementKind kind = node.getKind();
            if ((kind == ElementKind.PARAMETER || kind == ElementKind.PROPERTY) && StringUtils.hasText(node.getName())) {
                if (!field.isEmpty()) {
                    field.append('.');
                }
                field.append(node.getName());
            }
        }
        return field.toString();
    }
}
