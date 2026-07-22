package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.resilience.ServiceResilience;
import com.jobbuddy.backend.modules.chat.service.RuntimeToolClient;
import com.jobbuddy.backend.modules.chat.service.impl.RuntimeToolClientImpl;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

class RuntimeToolClientTest {

  private AgentServiceProperties properties() {
    AgentServiceProperties properties = new AgentServiceProperties();
    properties.setRuntimeUrl("http://runtime.local");
    return properties;
  }

  @Test
  @SuppressWarnings("unchecked")
  void invokeReturnsToolDataOnSuccess() {
    AgentServiceProperties properties = properties();
    RestTemplate restTemplate = mock(RestTemplate.class);
    RuntimeToolClient client =
        new RuntimeToolClientImpl(restTemplate, properties, new ServiceResilience(properties));

    Map<String, Object> data = new LinkedHashMap<String, Object>();
    data.put("status", "ok");
    Map<String, Object> envelope = new LinkedHashMap<String, Object>();
    envelope.put("data", data);
    when(restTemplate.postForObject(
            eq("http://runtime.local/v1/runtime/tools/boss_browser/invoke"), any(), eq(Map.class)))
        .thenReturn(envelope);

    Map<String, Object> result =
        client.invoke("boss_browser", Collections.<String, Object>emptyMap(), "sess-1", null);

    assertEquals("ok", result.get("status"));
  }

  @Test
  void invokeRethrowsAndFeedsCircuitOnFailure() {
    AgentServiceProperties properties = properties();
    properties.setCircuitFailureThreshold(1);
    RestTemplate restTemplate = mock(RestTemplate.class);
    ServiceResilience resilience = new ServiceResilience(properties);
    RuntimeToolClient client = new RuntimeToolClientImpl(restTemplate, properties, resilience);

    when(restTemplate.postForObject(any(String.class), any(), eq(Map.class)))
        .thenThrow(new RuntimeException("runtime down"));

    assertThrows(
        RuntimeException.class,
        () -> client.invoke("boss_browser", Collections.<String, Object>emptyMap(), null, null));
    assertTrue(resilience.isOpen("agent-runtime-tool"));
  }

  @Test
  void invokeFastFailsWhenCircuitOpen() {
    AgentServiceProperties properties = properties();
    properties.setCircuitFailureThreshold(1);
    RestTemplate restTemplate = mock(RestTemplate.class);
    ServiceResilience resilience = new ServiceResilience(properties);
    resilience.recordFailure("agent-runtime-tool");

    RuntimeToolClient client = new RuntimeToolClientImpl(restTemplate, properties, resilience);
    RuntimeException error =
        assertThrows(
            RuntimeException.class,
            () ->
                client.invoke("boss_browser", Collections.<String, Object>emptyMap(), null, null));
    assertTrue(error.getMessage().contains("熔断"));
  }
}
