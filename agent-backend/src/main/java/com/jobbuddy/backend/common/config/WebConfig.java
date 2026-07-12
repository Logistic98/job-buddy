package com.jobbuddy.backend.common.config;

import com.jobbuddy.backend.common.security.ApiAuthenticationInterceptor;
import com.jobbuddy.backend.common.security.ApiAuthorizationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
  private final ApiAuthenticationInterceptor apiAuthenticationInterceptor;
  private final ApiAuthorizationInterceptor apiAuthorizationInterceptor;

  public WebConfig(
      ApiAuthenticationInterceptor apiAuthenticationInterceptor,
      ApiAuthorizationInterceptor apiAuthorizationInterceptor) {
    this.apiAuthenticationInterceptor = apiAuthenticationInterceptor;
    this.apiAuthorizationInterceptor = apiAuthorizationInterceptor;
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping("/api/**")
        .allowedOriginPatterns("*")
        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
        .allowedHeaders("*");
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(apiAuthenticationInterceptor).addPathPatterns("/api/**").order(0);
    registry.addInterceptor(apiAuthorizationInterceptor).addPathPatterns("/api/**").order(1);
  }
}
