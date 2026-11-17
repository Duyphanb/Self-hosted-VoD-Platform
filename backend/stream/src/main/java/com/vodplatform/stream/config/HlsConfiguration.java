package com.vodplatform.stream.config;

import com.vodplatform.stream.web.HlsRouteFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Registers before the multipart gate and security; never reads request parameters or bodies. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class HlsConfiguration {
    @Bean
    FilterRegistrationBean<HlsRouteFilter> hlsRouteFilter() {
        var registration = new FilterRegistrationBean<>(new HlsRouteFilter());
        registration.setName("hlsRouteFilter");
        registration.addUrlPatterns("/hls/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
