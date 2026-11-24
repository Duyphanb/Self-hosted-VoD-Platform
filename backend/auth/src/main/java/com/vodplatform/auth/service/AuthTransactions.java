package com.vodplatform.auth.service;

/**
 * The time bounds of the login, refresh and logout transactions.
 *
 * <p>The SPA aborts these requests after 10 s. The server must answer first: a refresh that commits after the
 * browser gave up rotates a cookie the browser never receives, and its next refresh is then treated as a replay.
 * <ul>
 *   <li>{@link #TIMEOUT_SECONDS}: Spring applies it as a JDBC query timeout, which also ends a lock wait, for
 *       example behind an operator transaction that holds the user row.</li>
 *   <li>{@link #NETWORK_TIMEOUT_MILLIS}: the socket read timeout of the transaction's connection, commit
 *       included, so a dead connection fails too.</li>
 * </ul>
 * Either failure is 503 {@code AUTH_STATE_UNAVAILABLE} (ADR-012 D6/D7).
 */
final class AuthTransactions {

    static final int TIMEOUT_SECONDS = 5;
    static final int NETWORK_TIMEOUT_MILLIS = 7_000;

    private AuthTransactions() {
    }
}
