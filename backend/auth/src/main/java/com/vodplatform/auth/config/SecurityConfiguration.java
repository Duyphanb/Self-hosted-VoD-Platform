package com.vodplatform.auth.config;

import com.vodplatform.auth.security.AccessTokenAuthenticationConverter;
import com.vodplatform.auth.security.ApiAccessDeniedHandler;
import com.vodplatform.auth.security.ApiAuthenticationEntryPoint;
import com.vodplatform.auth.security.AuthEndpoints;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(CorsProperties.class)
public class SecurityConfiguration {

    private final CorsProperties origins;
    private final com.vodplatform.shared.error.ApiErrorFactory errors;
    private final tools.jackson.databind.ObjectMapper mapper;

    public SecurityConfiguration(CorsProperties origins, com.vodplatform.shared.error.ApiErrorFactory errors,
                                 tools.jackson.databind.ObjectMapper mapper) {
        this.origins = origins; this.errors = errors; this.mapper = mapper;
    }

    private static final List<String> CORS_ALLOWED_METHODS = List.of(
            HttpMethod.GET.name(),
            HttpMethod.POST.name(),
            HttpMethod.PUT.name(),
            HttpMethod.DELETE.name(),
            HttpMethod.OPTIONS.name()
    );
    private static final List<String> CORS_ALLOWED_HEADERS = List.of(
            "Authorization",
            "Content-Type",
            "X-Request-ID"
    );

    private static final RequestMatcher PUBLIC_ENDPOINTS = new OrRequestMatcher(
            AuthEndpoints.POSTS,
            get("/api/v1/health"),
            get("/actuator/health"),
            get("/actuator/health/**")
    );

    /**
     * Admin operations are authorized by URL before argument resolution and validation run, so a
     * non-admin caller gets 403 even for an invalid body or a missing target. The terminal {@code *}
     * matches one segment, including an empty one: {@code PUT /api/v1/movies/} also requires admin,
     * while deeper paths such as {@code /api/v1/movies/{movieId}/progress} (user sub-resources) and
     * {@code /api/v1/movies/{movieId}/} do not match this rule. Method-level {@code @PreAuthorize}
     * remains the second layer.
     */
    static final RequestMatcher ADMIN_ENDPOINTS = new OrRequestMatcher(
            PathPatternRequestMatcher.pathPattern("/api/v1/admin/**"),
            post("/api/v1/movies"),
            put("/api/v1/movies/*"),
            delete("/api/v1/movies/*")
    );

    @Bean
    @Order(1)
    SecurityFilterChain publicEndpointSecurityFilterChain(
            HttpSecurity http,
            CorsConfigurationSource corsConfigurationSource
    ) throws Exception {
        applyStatelessDefaults(http, corsConfigurationSource);
        http.securityMatcher(PUBLIC_ENDPOINTS)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain bearerSecurityFilterChain(
            HttpSecurity http,
            ApiAuthenticationEntryPoint authenticationEntryPoint,
            ApiAccessDeniedHandler accessDeniedHandler,
            AccessTokenAuthenticationConverter authenticationConverter,
            JwtDecoder decoder,
            CorsConfigurationSource corsConfigurationSource
    ) throws Exception {
        applyStatelessDefaults(http, corsConfigurationSource);
        var bearerProvider = new JwtAuthenticationProvider(decoder);
        bearerProvider.setJwtAuthenticationConverter(authenticationConverter);
        var adminProvider = new JwtAuthenticationProvider(decoder);
        adminProvider.setJwtAuthenticationConverter(authenticationConverter::convertAdmin);

        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(ADMIN_ENDPOINTS).hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .authenticationManagerResolver(request -> ADMIN_ENDPOINTS.matches(request)
                                ? adminProvider::authenticate : bearerProvider::authenticate));
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(CORS_ALLOWED_METHODS);
        configuration.setAllowedHeaders(CORS_ALLOWED_HEADERS);
        configuration.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/**", configuration);
        source.registerCorsConfiguration("/hls/**", configuration);
        return source;
    }

    private void applyStatelessDefaults(
            HttpSecurity http,
            CorsConfigurationSource corsConfigurationSource
    ) throws Exception {
        http.addFilterBefore(new com.vodplatform.auth.security.AuthOriginFilter(origins, errors, mapper),
                        org.springframework.web.filter.CorsFilter.class)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                // SECURITY.md: HSTS is deferred and remains an edge decision, on both chains.
                .headers(headers -> headers.httpStrictTransportSecurity(hsts -> hsts.disable()))
                .csrf(csrf -> csrf.disable())
                .requestCache(requestCache -> requestCache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(formLogin -> formLogin.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .logout(logout -> logout.disable());
    }

    private static RequestMatcher get(String pattern) {
        return PathPatternRequestMatcher.pathPattern(HttpMethod.GET, pattern);
    }

    private static RequestMatcher post(String pattern) {
        return PathPatternRequestMatcher.pathPattern(HttpMethod.POST, pattern);
    }

    private static RequestMatcher put(String pattern) {
        return PathPatternRequestMatcher.pathPattern(HttpMethod.PUT, pattern);
    }

    private static RequestMatcher delete(String pattern) {
        return PathPatternRequestMatcher.pathPattern(HttpMethod.DELETE, pattern);
    }
}
