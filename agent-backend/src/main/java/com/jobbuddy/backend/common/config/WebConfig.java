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
  private final JobBuddyProperties properties;

  public WebConfig(
      ApiAuthenticationInterceptor apiAuthenticationInterceptor,
      ApiAuthorizationInterceptor apiAuthorizationInterceptor,
      JobBuddyProperties properties) {
    this.apiAuthenticationInterceptor = apiAuthenticationInterceptor;
    this.apiAuthorizationInterceptor = apiAuthorizationInterceptor;
    this.properties = properties;
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping("/api/**")
        .allowedOrigins(properties.getCorsAllowedOrigins().toArray(new String[0]))
        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
        .allowedHeaders("*")
        .allowCredentials(true);
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(apiAuthenticationInterceptor).addPathPatterns("/api/**").order(0);
    registry.addInterceptor(apiAuthorizationInterceptor).addPathPatterns("/api/**").order(1);
  }
}
