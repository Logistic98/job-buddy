package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.web.servlet.MockMvc;

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
        .andExpect(jsonPath("$.openapi").exists());

    mockMvc.perform(get("/doc.html")).andExpect(status().isOk());
  }
}
