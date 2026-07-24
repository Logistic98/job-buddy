package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.resilience.ServiceResilience;
import com.jobbuddy.backend.modules.chat.client.IntentClient;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class IntentClientTest {

  private AgentServiceProperties props() {
    AgentServiceProperties p = new AgentServiceProperties();
    p.setIntentUrl("http://intent.local");
    p.setMaxAttempts(2);
    p.setRetryBackoff(Duration.ZERO);
    p.setCircuitFailureThreshold(5);
    p.setCircuitOpenDuration(Duration.ofSeconds(10));
    return p;
  }

  @Test
  void shouldMapAgentIntentResponseToIntentResult() {
    AgentServiceProperties properties = props();
    RestTemplate restTemplate = new RestTemplate();
    MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
    server
        .expect(requestTo("http://intent.local/v1/intent/classify"))
        .andExpect(method(org.springframework.http.HttpMethod.POST))
        .andRespond(
            withSuccess(
                "{\"code\":0,\"message\":\"success\",\"data\":{\"domain\":\"job\",\"intent\":\"job.recommend\","
                    + "\"confidence\":0.82,\"secondary\":[\"job.consult\"],\"risk\":\"low\","
                    + "\"needs_clarification\":false,\"next_action\":\"call_get_recommend_jobs\","
                    + "\"router\":\"llm\",\"slots\":{\"city\":\"上海\"}}}",
                MediaType.APPLICATION_JSON));

    IntentClient client =
        new IntentClient(restTemplate, properties, new ServiceResilience(properties));
    IntentResult result = client.classify("帮我推荐上海的岗位");

    assertEquals("job", result.getDomain());
    assertEquals("job.recommend", result.getIntent());
    assertEquals(0.82, result.getConfidence(), 1e-9);
    assertEquals("low", result.getRisk());
    assertEquals(false, result.isNeedsClarification());
    assertEquals("call_get_recommend_jobs", result.getNextAction());
    assertEquals("job.consult", result.getSecondary().get(0));
    assertEquals("llm", result.getRouter());
    assertEquals("上海", result.getSlots().get("city"));
    server.verify();
  }

  @Test
  void shouldReturnNullWhenAgentIntentFails() {
    AgentServiceProperties properties = props();
    RestTemplate restTemplate = new RestTemplate();
    MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
    // 可重试调用会按 maxAttempts 发起多次请求，全部失败后返回 null。
    server
        .expect(
            org.springframework.test.web.client.ExpectedCount.manyTimes(),
            requestTo("http://intent.local/v1/intent/classify"))
        .andRespond(withServerError());

    IntentClient client =
        new IntentClient(restTemplate, properties, new ServiceResilience(properties));
    IntentResult result = client.classify("任意消息");

    assertNull(result);
  }

  @Test
  void shouldReturnNullWhenIntentUrlMissing() {
    AgentServiceProperties properties = props();
    properties.setIntentUrl("");
    RestTemplate restTemplate = new RestTemplate();

    IntentClient client =
        new IntentClient(restTemplate, properties, new ServiceResilience(properties));

    assertNull(client.classify("任意消息"));
  }

  @Test
  void shouldReturnNullWhenUnifiedResponseCodeFails() {
    AgentServiceProperties properties = props();
    RestTemplate restTemplate = new RestTemplate();
    MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
    server
        .expect(requestTo("http://intent.local/v1/intent/classify"))
        .andRespond(
            withSuccess(
                "{\"code\":5001,\"message\":\"failed\",\"data\":{\"domain\":\"job\",\"intent\":\"job.recommend\"}}",
                MediaType.APPLICATION_JSON));

    IntentClient client =
        new IntentClient(restTemplate, properties, new ServiceResilience(properties));

    assertNull(client.classify("任意消息"));
  }

  @Test
  void shouldReturnNullWhenDataMissing() {
    AgentServiceProperties properties = props();
    RestTemplate restTemplate = new RestTemplate();
    MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
    server
        .expect(requestTo("http://intent.local/v1/intent/classify"))
        .andRespond(
            withSuccess("{\"code\":0,\"message\":\"success\"}", MediaType.APPLICATION_JSON));

    IntentClient client =
        new IntentClient(restTemplate, properties, new ServiceResilience(properties));

    assertTrue(client.classify("任意消息") == null);
  }
}
