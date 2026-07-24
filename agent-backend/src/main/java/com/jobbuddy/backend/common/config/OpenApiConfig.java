package com.jobbuddy.backend.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 配置后端 OpenAPI 文档的基础信息和统一认证方式。 */
@Configuration
public class OpenApiConfig {
  /**
   * 构建供 SpringDoc 与 Knife4j 共用的 OpenAPI 定义。
   *
   * <p>业务接口默认使用 Bearer Token；无需认证的接口应在对应 Controller 方法上显式声明空安全要求。
   *
   * @return 后端服务的 OpenAPI 定义
   */
  @Bean
  public OpenAPI customOpenAPI() {
    SecurityScheme securityScheme =
        new SecurityScheme()
            .type(SecurityScheme.Type.HTTP)
            .scheme("bearer")
            .bearerFormat("JWT")
            .in(SecurityScheme.In.HEADER)
            .name("Authorization")
            .description("登录后获取的访问令牌，格式为 Bearer {token}");
    Components components = new Components().addSecuritySchemes("Authorization", securityScheme);
    SecurityRequirement securityRequirement = new SecurityRequirement().addList("Authorization");
    return new OpenAPI()
        .info(
            new Info()
                .title("Job Buddy Backend API")
                .version("1.0.0")
                .description("Job Buddy 业务后端与 BFF 接口，统一响应结构包含 code、message 和 data。"))
        .components(components)
        .addSecurityItem(securityRequirement);
  }
}
