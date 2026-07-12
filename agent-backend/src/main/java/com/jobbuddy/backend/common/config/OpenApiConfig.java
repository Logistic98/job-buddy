package com.jobbuddy.backend.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
  @Bean
  public OpenAPI customOpenAPI() {
    SecurityScheme securityScheme =
        new SecurityScheme()
            .type(SecurityScheme.Type.HTTP)
            .scheme("bearer")
            .bearerFormat("JWT")
            .in(SecurityScheme.In.HEADER)
            .name("Authorization");
    Components components = new Components().addSecuritySchemes("Authorization", securityScheme);
    SecurityRequirement securityRequirement = new SecurityRequirement().addList("Authorization");
    return new OpenAPI()
        .info(
            new Info()
                .title("Job Buddy Backend API")
                .version("1.0.0")
                .description("Job Buddy backend service API"))
        .components(components)
        .addSecurityItem(securityRequirement);
  }
}
