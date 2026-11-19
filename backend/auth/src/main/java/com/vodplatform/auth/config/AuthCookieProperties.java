package com.vodplatform.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("auth.cookie")
public record AuthCookieProperties(Boolean secure) {
    public AuthCookieProperties { secure = secure == null || secure; }
}
