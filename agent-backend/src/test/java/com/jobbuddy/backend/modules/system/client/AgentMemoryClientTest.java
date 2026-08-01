package com.jobbuddy.backend.modules.system.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.resilience.ServiceResilience;
import com.jobbuddy.backend.modules.system.dto.request.SystemMemoryRequest;
import com.jobbuddy.backend.modules.system.dto.response.SystemMemoryResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * 验证 AgentMemoryClient 的核心行为、异常路径与边界条件。
 */
class AgentMemoryClientTest {

  /**
   * 验证 AgentMemoryClient 的数据转换与协议契约。
   */
  @Test
  void listUsesOwnedLongTermScopeAndMapsMetadata() {
    RestTemplate restTemplate = new RestTemplate();
    MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    AgentMemoryClient client = client(restTemplate);
    server
        .expect(requestTo("http://127.0.0.1:8030/v1/memories?scope=long_term&limit=1000"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-Tenant-Id", "tenant-a"))
        .andExpect(header("X-Operator-Id", "user-a"))
        .andRespond(
            withSuccess(
                "{\"code\":200,\"message\":\"success\",\"data\":[{"
                    + "\"id\":\"mem_1\",\"scope\":\"long_term\","
                    + "\"content\":\"排除外包岗位\",\"source\":\"manual\",\"enabled\":true,"
                    + "\"created_at\":\"2026-07-24T00:00:00Z\"}]}",
                MediaType.APPLICATION_JSON));

    List<SystemMemoryResponse> items = client.list("tenant-a", "user-a");

    assertEquals(1, items.size());
    assertEquals("manual", items.get(0).getSource());
    server.verify();
  }

  /**
   * 验证 AgentMemoryClient 的持久化与状态变更规则。
   */
  @Test
  void createWritesLongTermMetadata() {
    RestTemplate restTemplate = new RestTemplate();
    MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    AgentMemoryClient client = client(restTemplate);
    server
        .expect(requestTo("http://127.0.0.1:8030/v1/memories"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("X-Tenant-Id", "tenant-a"))
        .andExpect(header("X-Operator-Id", "user-a"))
        .andRespond(
            withSuccess(
                "{\"code\":200,\"message\":\"success\",\"data\":{"
                    + "\"id\":\"mem_2\",\"scope\":\"long_term\","
                    + "\"content\":\"优先远程岗位\",\"source\":\"manual\",\"enabled\":true}}",
                MediaType.APPLICATION_JSON));
    SystemMemoryRequest request = new SystemMemoryRequest();
    request.setContent("优先远程岗位");
    request.setSource("manual");

    SystemMemoryResponse created = client.create("tenant-a", "user-a", request);

    assertEquals("mem_2", created.getId());
    server.verify();
  }

  /**
   * 验证更新记忆时使用受属主约束的 PUT 接口。
   */
  @Test
  void updateWritesContentToOwnedMemory() {
    RestTemplate restTemplate = new RestTemplate();
    MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    AgentMemoryClient client = client(restTemplate);
    server
        .expect(requestTo("http://127.0.0.1:8030/v1/memories/mem_2"))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(header("X-Tenant-Id", "tenant-a"))
        .andExpect(header("X-Operator-Id", "user-a"))
        .andExpect(content().json("{\"content\":\"优先杭州岗位\"}"))
        .andRespond(
            withSuccess(
                "{\"code\":200,\"message\":\"success\",\"data\":{"
                    + "\"id\":\"mem_2\",\"scope\":\"long_term\","
                    + "\"content\":\"优先杭州岗位\",\"source\":\"manual\",\"enabled\":true}}",
                MediaType.APPLICATION_JSON));
    SystemMemoryRequest request = new SystemMemoryRequest();
    request.setContent("优先杭州岗位");

    SystemMemoryResponse updated = client.update("tenant-a", "user-a", "mem_2", request);

    assertEquals("优先杭州岗位", updated.getContent());
    server.verify();
  }

  /**
   * 验证删除记忆时使用受属主约束的 DELETE 接口。
   */
  @Test
  void deleteRemovesOnlyTheOwnedMemory() {
    RestTemplate restTemplate = new RestTemplate();
    MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    AgentMemoryClient client = client(restTemplate);
    server
        .expect(requestTo("http://127.0.0.1:8030/v1/memories/mem_2"))
        .andExpect(method(HttpMethod.DELETE))
        .andExpect(header("X-Tenant-Id", "tenant-a"))
        .andExpect(header("X-Operator-Id", "user-a"))
        .andRespond(
            withSuccess(
                "{\"code\":200,\"message\":\"success\",\"data\":{"
                    + "\"id\":\"mem_2\",\"deleted\":true}}",
                MediaType.APPLICATION_JSON));

    client.delete("tenant-a", "user-a", "mem_2");

    server.verify();
  }

  /**
   * 验证 AgentMemoryClient 的失败恢复、超时与降级边界。
   */
  @Test
  void listRetriesOneTransientFailureWithoutMaskingTheResult() {
    RestTemplate restTemplate = new RestTemplate();
    MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    AgentMemoryClient client = client(restTemplate);
    String url = "http://127.0.0.1:8030/v1/memories?scope=long_term&limit=1000";
    server.expect(requestTo(url)).andRespond(withServerError());
    server
        .expect(requestTo(url))
        .andRespond(
            withSuccess(
                "{\"code\":200,\"message\":\"success\",\"data\":[]}", MediaType.APPLICATION_JSON));

    assertEquals(0, client.list("tenant-a", "user-a").size());
    server.verify();
  }

  /**
   * 验证 AgentMemoryClient 的失败恢复、超时与降级边界。
   */
  @Test
  void createDoesNotRetryAnAmbiguousWriteFailure() {
    RestTemplate restTemplate = new RestTemplate();
    MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    AgentMemoryClient client = client(restTemplate);
    server
        .expect(requestTo("http://127.0.0.1:8030/v1/memories"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withServerError());
    SystemMemoryRequest request = new SystemMemoryRequest();
    request.setContent("优先远程岗位");

    assertThrows(IllegalStateException.class, () -> client.create("tenant-a", "user-a", request));
    server.verify();
  }

  /**
   * 验证 AgentMemoryClient 的失败恢复、超时与降级边界。
   */
  @Test
  void deterministicClientErrorDoesNotRetryOrOpenTheAvailabilityCircuit() {
    RestTemplate restTemplate = new RestTemplate();
    MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    AgentServiceProperties properties = properties();
    properties.setCircuitFailureThreshold(1);
    ServiceResilience resilience = new ServiceResilience(properties);
    AgentMemoryClient client = new AgentMemoryClient(restTemplate, properties, resilience);
    server
        .expect(requestTo("http://127.0.0.1:8030/v1/memories?scope=long_term&limit=1000"))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

    assertThrows(IllegalStateException.class, () -> client.list("tenant-a", "user-a"));

    server.verify();
    assertEquals(false, resilience.isOpen("agent-memory"));
  }

  /**
   * 验证客户端。
   *
   * @param restTemplate HTTP 请求客户端
   * @return Agent 记忆客户端
   */
  private AgentMemoryClient client(RestTemplate restTemplate) {
    AgentServiceProperties properties = properties();
    return new AgentMemoryClient(restTemplate, properties, new ServiceResilience(properties));
  }

  /**
   * 验证配置属性。
   *
   * @return 测试配置
   */
  private AgentServiceProperties properties() {
    AgentServiceProperties properties = new AgentServiceProperties();
    properties.setMemoryUrl("http://127.0.0.1:8030");
    properties.setMaxAttempts(2);
    properties.setRetryBackoff(Duration.ZERO);
    properties.setCircuitFailureThreshold(5);
    return properties;
  }
}
