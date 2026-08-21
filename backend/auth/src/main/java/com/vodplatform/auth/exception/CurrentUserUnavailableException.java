package com.vodplatform.auth.exception;

public class CurrentUserUnavailableException extends RuntimeException {

    public CurrentUserUnavailableException() {
        super("Authentication required or invalid token");
    }
}
