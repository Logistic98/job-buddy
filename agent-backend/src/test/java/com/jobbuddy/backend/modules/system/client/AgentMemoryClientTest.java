package com.jobbuddy.backend.modules.system.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

class AgentMemoryClientTest {

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
                    + "\"id\":\"mem_1\",\"scope\":\"long_term\",\"category\":\"constraint\","
                    + "\"content\":\"排除外包岗位\",\"source\":\"manual\",\"enabled\":true,"
                    + "\"created_at\":\"2026-07-24T00:00:00Z\"}]}",
                MediaType.APPLICATION_JSON));

    List<SystemMemoryResponse> items = client.list("tenant-a", "user-a");

    assertEquals(1, items.size());
    assertEquals("constraint", items.get(0).getType());
    assertEquals("manual", items.get(0).getSource());
    server.verify();
  }

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
                    + "\"id\":\"mem_2\",\"scope\":\"long_term\",\"category\":\"preference\","
                    + "\"content\":\"优先远程岗位\",\"source\":\"manual\",\"enabled\":true}}",
                MediaType.APPLICATION_JSON));
    SystemMemoryRequest request = new SystemMemoryRequest();
    request.setType("preference");
    request.setContent("优先远程岗位");
    request.setSource("manual");

    SystemMemoryResponse created = client.create("tenant-a", "user-a", request);

    assertEquals("mem_2", created.getId());
    assertEquals("preference", created.getType());
    server.verify();
  }

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
    request.setType("preference");
    request.setContent("优先远程岗位");

    assertThrows(IllegalStateException.class, () -> client.create("tenant-a", "user-a", request));
    server.verify();
  }

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

  private AgentMemoryClient client(RestTemplate restTemplate) {
    AgentServiceProperties properties = properties();
    return new AgentMemoryClient(restTemplate, properties, new ServiceResilience(properties));
  }

  private AgentServiceProperties properties() {
    AgentServiceProperties properties = new AgentServiceProperties();
    properties.setMemoryUrl("http://127.0.0.1:8030");
    properties.setMaxAttempts(2);
    properties.setRetryBackoff(Duration.ZERO);
    properties.setCircuitFailureThreshold(5);
    return properties;
  }
}
