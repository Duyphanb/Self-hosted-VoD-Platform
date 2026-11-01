package com.vodplatform.common.web;

import com.vodplatform.shared.error.ApiErrorFactory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import tools.jackson.databind.ObjectMapper;

/** Servlet-wide gate, after character encoding and before form, CORS and security filters. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class MultipartRouteConfiguration {

    @Bean
    FilterRegistrationBean<MultipartRouteFilter> multipartRouteFilter(
            ApiErrorFactory errors, ObjectMapper mapper) {
        var registration = new FilterRegistrationBean<>(new MultipartRouteFilter(errors, mapper));
        registration.setName("multipartRouteFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}
