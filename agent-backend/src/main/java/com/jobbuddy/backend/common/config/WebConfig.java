package com.jobbuddy.backend.common.config;

import com.jobbuddy.backend.common.security.ApiAuthenticationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final ApiAuthenticationInterceptor apiAuthenticationInterceptor;

    public WebConfig(ApiAuthenticationInterceptor apiAuthenticationInterceptor) {
        this.apiAuthenticationInterceptor = apiAuthenticationInterceptor;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiAuthenticationInterceptor)
                .addPathPatterns("/api/**");
    }
}
