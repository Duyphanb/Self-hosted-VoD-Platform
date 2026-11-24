package com.vodplatform.auth.service;

/**
 * The statement time bound of the login, refresh and logout transactions.
 *
 * <p>Spring applies it as a JDBC query timeout, which cancels a lock wait (for example behind an operator
 * transaction that holds the user row) cleanly and keeps the connection. The request as a whole, pool wait and
 * commit included, is bounded by {@link AuthWriteDeadline}.
 */
final class AuthTransactions {

    static final int TIMEOUT_SECONDS = 5;

    private AuthTransactions() {
    }
}
