package com.vodplatform.auth.web;

import com.vodplatform.auth.service.AuthWriteDeadline;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Starts the {@link AuthWriteDeadline} as the request reaches the application, before security and before any
 * connection-pool wait. Only the login, refresh and logout transactions read it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthWriteDeadlineFilter extends OncePerRequestFilter {

    private final AuthWriteDeadline deadline;

    // Lazy: embedded Tomcat creates filters while it starts, before the shared EntityManager can be injected.
    public AuthWriteDeadlineFilter(@Lazy AuthWriteDeadline deadline) {
        this.deadline = deadline;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        deadline.startRequest();
        try {
            chain.doFilter(request, response);
        } finally {
            deadline.endRequest();
        }
    }
}
