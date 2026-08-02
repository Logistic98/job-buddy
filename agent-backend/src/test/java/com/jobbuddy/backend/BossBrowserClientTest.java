package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.resilience.ServiceResilience;
import com.jobbuddy.backend.modules.auth.client.BossBrowserClient;
import com.jobbuddy.backend.modules.auth.repository.AuthStateRepository;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestTemplate;

/**
 * 验证 BossBrowserClient 的核心行为、异常路径与边界条件。
 */
class BossBrowserClientTest {

  /**
   * 验证 BossBrowserClient 中工具的检索、筛选与排序规则。
   */
  @Test
  @SuppressWarnings("unchecked")
  void postSearchShouldInvokeRuntimeBossBrowserTool() {
    RestTemplate restTemplate = mock(RestTemplate.class);
    AgentServiceProperties properties = new AgentServiceProperties();
    properties.setRuntimeUrl("http://runtime.local");
    BossBrowserClient client =
        new BossBrowserClient(restTemplate, properties, new ServiceResilience(properties));

    Map<String, Object> output = new LinkedHashMap<String, Object>();
    output.put("code", 200);
    output.put("message", "success");
    output.put("data", Collections.singletonMap("count", 0));
    Map<String, Object> toolResult = new LinkedHashMap<String, Object>();
    toolResult.put("success", true);
    toolResult.put("output", output);
    Map<String, Object> response = new LinkedHashMap<String, Object>();
    response.put("code", 200);
    response.put("message", "ok");
    response.put("data", toolResult);
    when(restTemplate.postForObject(
            eq("http://runtime.local/v1/runtime/tools/boss_browser/invoke"),
            org.mockito.ArgumentMatchers.any(),
            eq(Map.class)))
        .thenReturn(response);

    Map<String, Object> request = new LinkedHashMap<String, Object>();
    request.put("query", "Go");
    request.put("city", "杭州");
    Map<String, Object> result = client.post("/search", request);

    assertEquals(200, result.get("code"));
    ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
    verify(restTemplate)
        .postForObject(
            eq("http://runtime.local/v1/runtime/tools/boss_browser/invoke"),
            bodyCaptor.capture(),
            eq(Map.class));
    Map<String, Object> body = bodyCaptor.getValue();
    Map<String, Object> arguments = (Map<String, Object>) body.get("arguments");
    assertEquals("search", arguments.get("operation"));
    assertEquals(request, arguments.get("payload"));
  }

  /**
   * 验证 BossBrowserClient 中凭据的身份认证与会话边界。
   */
  @Test
  @SuppressWarnings("unchecked")
  void requestShouldInjectCredentialFromCanonicalProvider() {
    RestTemplate restTemplate = mock(RestTemplate.class);
    AuthStateRepository repository = mock(AuthStateRepository.class);
    AgentServiceProperties properties = new AgentServiceProperties();
    properties.setRuntimeUrl("http://runtime.local");
    Map<String, Object> persisted = new LinkedHashMap<String, Object>();
    persisted.put("credentialJson", "{\"cookies\":{\"wt2\":\"persisted\"}}");
    when(repository.findByProvider("jackwener/boss-cli")).thenReturn(persisted);
    BossBrowserClient client =
        new BossBrowserClient(
            restTemplate, properties, new ServiceResilience(properties), repository);

    Map<String, Object> toolResult = new LinkedHashMap<String, Object>();
    toolResult.put("success", true);
    toolResult.put("output", Collections.singletonMap("code", 200));
    Map<String, Object> response = new LinkedHashMap<String, Object>();
    response.put("data", toolResult);
    when(restTemplate.postForObject(
            eq("http://runtime.local/v1/runtime/tools/boss_browser/invoke"),
            org.mockito.ArgumentMatchers.any(),
            eq(Map.class)))
        .thenReturn(response);

    client.post("/search", Collections.<String, Object>singletonMap("query", "Java"));

    ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
    verify(restTemplate)
        .postForObject(
            eq("http://runtime.local/v1/runtime/tools/boss_browser/invoke"),
            bodyCaptor.capture(),
            eq(Map.class));
    Map<String, Object> arguments = (Map<String, Object>) bodyCaptor.getValue().get("arguments");
    Map<String, Object> payload = (Map<String, Object>) arguments.get("payload");
    assertEquals("{\"cookies\":{\"wt2\":\"persisted\"}}", payload.get("credential_json"));
    verify(repository).findByProvider("jackwener/boss-cli");
  }

  /**
   * 成功刷新后的内部凭据必须加密回写，并在返回业务 Service 前剥离。
   */
  @Test
  @SuppressWarnings("unchecked")
  void successfulSearchShouldPersistAndStripRefreshedCredential() {
    RestTemplate restTemplate = mock(RestTemplate.class);
    AuthStateRepository repository = mock(AuthStateRepository.class);
    AgentServiceProperties properties = new AgentServiceProperties();
    properties.setRuntimeUrl("http://runtime.local");
    Map<String, Object> persisted = new LinkedHashMap<String, Object>();
    persisted.put(
        "credentialJson", "{\"cookies\":{\"wt2\":\"identity\",\"__zp_stoken__\":\"expired\"}}");
    persisted.put("metadata", Collections.singletonMap("source", "qr"));
    when(repository.findByProvider("jackwener/boss-cli")).thenReturn(persisted);
    BossBrowserClient client =
        new BossBrowserClient(
            restTemplate, properties, new ServiceResilience(properties), repository);

    Map<String, Object> outputData = new LinkedHashMap<String, Object>();
    outputData.put("count", 0);
    outputData.put(
        "credential_json", "{\"cookies\":{\"wt2\":\"identity\",\"__zp_stoken__\":\"fresh\"}}");
    Map<String, Object> output = new LinkedHashMap<String, Object>();
    output.put("code", 200);
    output.put("message", "success");
    output.put("data", outputData);
    Map<String, Object> toolResult = new LinkedHashMap<String, Object>();
    toolResult.put("success", true);
    toolResult.put("output", output);
    Map<String, Object> response = new LinkedHashMap<String, Object>();
    response.put("data", toolResult);
    when(restTemplate.postForObject(
            eq("http://runtime.local/v1/runtime/tools/boss_browser/invoke"),
            org.mockito.ArgumentMatchers.any(),
            eq(Map.class)))
        .thenReturn(response);

    Map<String, Object> result =
        client.post("/search", Collections.<String, Object>singletonMap("query", "大模型应用开发"));

    Map<String, Object> resultData = (Map<String, Object>) result.get("data");
    assertEquals(0, resultData.get("count"));
    assertFalse(resultData.containsKey("credential_json"));
    verify(repository)
        .save(
            eq("jackwener/boss-cli"),
            eq("logged_in"),
            eq("{\"cookies\":{\"wt2\":\"identity\",\"__zp_stoken__\":\"fresh\"}}"),
            anyMap());
  }

  /**
   * 验证 BossBrowserClient 中工具的失败恢复、超时与降级边界。
   */
  @Test
  void runtimeToolFailureShouldReturnOldEnvelopeShape() {
    RestTemplate restTemplate = mock(RestTemplate.class);
    AgentServiceProperties properties = new AgentServiceProperties();
    properties.setRuntimeUrl("http://runtime.local");
    BossBrowserClient client =
        new BossBrowserClient(restTemplate, properties, new ServiceResilience(properties));

    Map<String, Object> toolResult = new LinkedHashMap<String, Object>();
    toolResult.put("success", false);
    toolResult.put("error", "boom");
    Map<String, Object> response = new LinkedHashMap<String, Object>();
    response.put("data", toolResult);
    when(restTemplate.postForObject(
            eq("http://runtime.local/v1/runtime/tools/boss_browser/invoke"),
            org.mockito.ArgumentMatchers.any(),
            eq(Map.class)))
        .thenReturn(response);

    Map<String, Object> result = client.get("/status");

    assertEquals(5001, result.get("code"));
    assertTrue(String.valueOf(result.get("message")).contains("boom"));
  }
}
