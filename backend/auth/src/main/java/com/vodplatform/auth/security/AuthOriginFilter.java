package com.vodplatform.auth.security;

import com.vodplatform.auth.config.CorsProperties;
import com.vodplatform.shared.error.ApiErrorFactory;
import com.vodplatform.shared.web.RequestIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** ADR-012 D3, before CORS: auth origin rejections always use the ApiError contract. */
public class AuthOriginFilter extends OncePerRequestFilter {
    private final CorsProperties origins;
    private final ApiErrorFactory errors;
    private final ObjectMapper mapper;

    public AuthOriginFilter(CorsProperties origins, ApiErrorFactory errors, ObjectMapper mapper) {
        this.origins = origins; this.errors = errors; this.mapper = mapper;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        if (AuthEndpoints.POSTS.matches(request)) {
            var values = Collections.list(request.getHeaders("Origin"));
            boolean required = AuthEndpoints.REQUIRE_ORIGIN.matches(request);
            boolean rejected = "cross-site".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site"))
                    || values.size() > 1 || (required && values.isEmpty());
            if (!values.isEmpty()) {
                try {
                    String normalized = new CorsProperties(List.of(values.getFirst())).allowedOrigins().getFirst();
                    rejected |= !origins.allowedOrigins().contains(normalized);
                } catch (IllegalArgumentException | java.util.NoSuchElementException invalid) { rejected = true; }
            }
            if (rejected) {
                response.setStatus(403);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                mapper.writeValue(response.getOutputStream(), errors.create(HttpStatus.FORBIDDEN,
                        "ORIGIN_NOT_ALLOWED", "Origin is not allowed", List.of(),
                        RequestIds.existing(request).orElse(null)));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
