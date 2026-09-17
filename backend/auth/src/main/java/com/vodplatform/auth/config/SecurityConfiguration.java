package com.vodplatform.auth.config;

import com.vodplatform.auth.security.AccessTokenAuthenticationConverter;
import com.vodplatform.auth.security.ApiAccessDeniedHandler;
import com.vodplatform.auth.security.ApiAuthenticationEntryPoint;
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
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
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
            post("/api/v1/auth/register"),
            post("/api/v1/auth/login"),
            post("/api/v1/auth/refresh"),
            post("/api/v1/auth/logout"),
            get("/api/v1/health"),
            get("/actuator/health"),
            get("/actuator/health/**")
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
            CorsConfigurationSource corsConfigurationSource
    ) throws Exception {
        applyStatelessDefaults(http, corsConfigurationSource);
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter)));
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
        http.cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf.disable())
                .requestCache(requestCache -> requestCache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(formLogin -> formLogin.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .logout(logout -> logout.disable());
    }

    private static RequestMatcher get(String pattern) {
        return new AntPathRequestMatcher(pattern, HttpMethod.GET.name());
    }

    private static RequestMatcher post(String pattern) {
        return new AntPathRequestMatcher(pattern, HttpMethod.POST.name());
    }
}
