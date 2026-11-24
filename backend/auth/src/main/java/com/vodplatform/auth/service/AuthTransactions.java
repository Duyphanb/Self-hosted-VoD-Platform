package com.vodplatform.auth.service;

/**
 * The time bound of the login, refresh and logout transactions.
 *
 * <p>Spring applies it as a JDBC query timeout, which also ends a lock wait. A transaction that cannot finish
 * in time fails as 503 {@code AUTH_STATE_UNAVAILABLE} (ADR-012 D6/D7) well before the edge's 60-second read
 * timeout, instead of waiting behind, for example, an operator transaction that holds the user row.
 */
final class AuthTransactions {

    static final int TIMEOUT_SECONDS = 10;

    private AuthTransactions() {
    }
}
