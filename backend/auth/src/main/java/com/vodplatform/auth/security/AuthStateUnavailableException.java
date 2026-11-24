package com.vodplatform.auth.security;

import org.springframework.security.core.AuthenticationException;

public class AuthStateUnavailableException extends AuthenticationException {

    public static final String CODE = "AUTH_STATE_UNAVAILABLE";
    public static final String MESSAGE = "Authentication state is unavailable";

    public AuthStateUnavailableException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
