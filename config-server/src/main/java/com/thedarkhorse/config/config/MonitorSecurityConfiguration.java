package com.thedarkhorse.config.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MonitorSecurityConfiguration {

    @Bean
    public FilterRegistrationBean<MonitorSignatureFilter> monitorSignatureFilter(
            @Value("${monitor.secret}") String secret) {
        FilterRegistrationBean<MonitorSignatureFilter> registration =
                new FilterRegistrationBean<>(new MonitorSignatureFilter(secret));
        registration.addUrlPatterns("/monitor");
        return registration;
    }
}
