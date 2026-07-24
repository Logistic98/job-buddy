package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:boot3compat;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.flyway.enabled=false",
      "spring.sql.init.mode=never",
      "job-buddy.service-monitor.initial-delay-ms=3600000",
      "spring.data.redis.host=127.0.0.2",
      "spring.data.redis.port=6381",
      "spring.data.redis.repositories.enabled=false",
      "management.health.redis.enabled=false"
    })
@AutoConfigureMockMvc
class SpringBoot3ContractTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private RedisConnectionFactory redisConnectionFactory;

  @Autowired private RequestMappingHandlerMapping requestMappingHandlerMapping;

  @Test
  void shouldBindSpringBoot3RedisProperties() {
    LettuceConnectionFactory connectionFactory =
        assertInstanceOf(LettuceConnectionFactory.class, redisConnectionFactory);

    assertEquals("127.0.0.2", connectionFactory.getHostName());
    assertEquals(6381, connectionFactory.getPort());
  }

  @Test
  void shouldExposeApplicationAndActuatorHealthEndpoints() throws Exception {
    mockMvc
        .perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200));

    mockMvc
        .perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void shouldGenerateOpenApiDocumentWithJakartaStack() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.openapi").exists())
        .andExpect(
            jsonPath("$.info.description")
                .value("Job Buddy 业务后端与 BFF 接口，统一响应结构包含 code、message 和 data。"))
        .andExpect(
            jsonPath("$.components.securitySchemes.Authorization.description")
                .value("登录后获取的访问令牌，格式为 Bearer {token}"))
        .andExpect(
            jsonPath("$.paths['/api/workspace/state/{stateKey}'].get.summary").value("查询工作区状态"))
        .andExpect(
            jsonPath("$.paths['/api/workspace/state/{stateKey}'].put.summary").value("保存工作区状态"))
        .andExpect(
            jsonPath("$.paths['/api/workspace/state/{stateKey}'].delete.summary").value("删除工作区状态"))
        .andExpect(jsonPath("$.paths['/api/admin/rbac/roles'].get.summary").value("查询角色列表"))
        .andExpect(jsonPath("$.paths['/api/admin/rbac/menus'].post.summary").value("创建菜单"));

    mockMvc.perform(get("/doc.html")).andExpect(status().isOk());
  }

  @Test
  void shouldDocumentEveryBackendControllerAndOperation() {
    List<String> missingTags = new ArrayList<String>();
    List<String> missingOperations = new ArrayList<String>();

    for (Map.Entry<RequestMappingInfo, HandlerMethod> entry :
        requestMappingHandlerMapping.getHandlerMethods().entrySet()) {
      HandlerMethod handler = entry.getValue();
      Class<?> controllerType = handler.getBeanType();
      if (!controllerType.getPackageName().startsWith("com.jobbuddy.backend")
          || !controllerType.getSimpleName().endsWith("Controller")) {
        continue;
      }

      Tag tag = AnnotatedElementUtils.findMergedAnnotation(controllerType, Tag.class);
      if (tag == null || tag.name().isBlank()) {
        missingTags.add(controllerType.getSimpleName());
      }

      Method method = handler.getMethod();
      Operation operation = AnnotatedElementUtils.findMergedAnnotation(method, Operation.class);
      if (operation == null || operation.summary().isBlank()) {
        missingOperations.add(controllerType.getSimpleName() + "#" + method.getName());
      }
    }

    assertTrue(missingTags.isEmpty(), "缺少 @Tag 的 Controller: " + missingTags);
    assertTrue(missingOperations.isEmpty(), "缺少 @Operation 的接口: " + missingOperations);
  }
}
