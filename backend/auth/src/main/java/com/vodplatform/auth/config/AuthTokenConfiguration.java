package com.vodplatform.auth.config;

import com.github.benmanes.caffeine.cache.Ticker;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthTokenProperties.class)
public class AuthTokenConfiguration {

    @Bean
    SecureRandom authSecureRandom() {
        return new SecureRandom();
    }

    @Bean
    Ticker authSessionTicker() {
        return Ticker.systemTicker();
    }

    @Bean
    SecretKey jwtSecretKey(AuthTokenProperties properties) {
        return new SecretKeySpec(
                properties.secret().getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"
        );
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(jwtSecretKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSecretKey, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        var timestamps = new JwtTimestampValidator(Duration.ofSeconds(5));
        timestamps.setClock(clock);
        decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(timestamps, requiredExpirationValidator()));
        return decoder;
    }

    private OAuth2TokenValidator<Jwt> requiredExpirationValidator() {
        return jwt -> jwt.getExpiresAt() == null
                ? OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        "invalid_token",
                        "Access token expiration is required",
                        null
                ))
                : OAuth2TokenValidatorResult.success();
    }
}
