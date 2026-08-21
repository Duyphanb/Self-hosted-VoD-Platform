package com.vodplatform.auth.config;

import com.vodplatform.auth.security.AccessTokenAuthenticationConverter;
import com.vodplatform.auth.security.ApiAccessDeniedHandler;
import com.vodplatform.auth.security.ApiAuthenticationEntryPoint;
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

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfiguration {

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
    SecurityFilterChain publicEndpointSecurityFilterChain(HttpSecurity http) throws Exception {
        applyStatelessDefaults(http);
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
            AccessTokenAuthenticationConverter authenticationConverter
    ) throws Exception {
        applyStatelessDefaults(http);
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter)));
        return http.build();
    }

    private void applyStatelessDefaults(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
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
