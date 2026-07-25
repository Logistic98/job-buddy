package com.jobbuddy.backend.common.config;

import com.jobbuddy.backend.common.security.ApiAuthenticationInterceptor;
import com.jobbuddy.backend.common.security.ApiAuthorizationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 配置 Web。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
  private final ApiAuthenticationInterceptor apiAuthenticationInterceptor;
  private final ApiAuthorizationInterceptor apiAuthorizationInterceptor;
  private final JobBuddyProperties properties;

  /**
   * 创建 Web 配置实例。
   *
   * @param apiAuthenticationInterceptor API 认证拦截器
   * @param apiAuthorizationInterceptor API 授权拦截器
   * @param properties 配置属性
   */
  public WebConfig(
      ApiAuthenticationInterceptor apiAuthenticationInterceptor,
      ApiAuthorizationInterceptor apiAuthorizationInterceptor,
      JobBuddyProperties properties) {
    this.apiAuthenticationInterceptor = apiAuthenticationInterceptor;
    this.apiAuthorizationInterceptor = apiAuthorizationInterceptor;
    this.properties = properties;
  }

  /**
   * 添加 CORS 映射。
   *
   * @param registry 注册表
   */
  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping("/api/**")
        .allowedOrigins(properties.getCorsAllowedOrigins().toArray(new String[0]))
        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
        .allowedHeaders("*")
        .allowCredentials(true);
  }

  /**
   * 添加拦截器。
   *
   * @param registry 注册表
   */
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(apiAuthenticationInterceptor).addPathPatterns("/api/**").order(0);
    registry.addInterceptor(apiAuthorizationInterceptor).addPathPatterns("/api/**").order(1);
  }
}
