package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.resilience.ServiceResilience;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeToolArguments;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeToolResult;
import com.jobbuddy.backend.modules.chat.service.RuntimeToolClient;
import com.jobbuddy.backend.modules.chat.service.impl.RuntimeToolClientImpl;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestTemplate;

class RuntimeToolClientTest {
  private static final JsonCodec JSON = new JsonCodec();

  private AgentServiceProperties properties() {
    AgentServiceProperties properties = new AgentServiceProperties();
    properties.setRuntimeUrl("http://runtime.local");
    return properties;
  }

  @Test
  void invokeReturnsToolDataOnSuccess() {
    AgentServiceProperties properties = properties();
    RestTemplate restTemplate = mock(RestTemplate.class);
    RuntimeToolClient client =
        new RuntimeToolClientImpl(restTemplate, properties, new ServiceResilience(properties));

    JsonNode envelope =
        JSON.readTree(
            "{\"data\":{\"tool_call_id\":\"call-1\",\"tool_name\":\"boss_browser\","
                + "\"success\":true,\"status\":\"ok\",\"data\":{\"jobs\":[1]},"
                + "\"future_tool_field\":{\"version\":2}}}");
    when(restTemplate.postForObject(
            eq("http://runtime.local/v1/runtime/tools/boss_browser/invoke"),
            any(),
            eq(JsonNode.class)))
        .thenReturn(envelope);

    Map<String, Object> arguments = new LinkedHashMap<String, Object>();
    arguments.put("query_text", "Java");
    RuntimeToolResult result =
        client.invoke(
            "boss_browser", RuntimeToolArguments.fromMap(arguments, JSON), "sess-1", null);
    Map<String, Object> resultMap = result.toMap(JSON);
    ArgumentCaptor<ObjectNode> bodyCaptor = ArgumentCaptor.forClass(ObjectNode.class);
    verify(restTemplate)
        .postForObject(
            eq("http://runtime.local/v1/runtime/tools/boss_browser/invoke"),
            bodyCaptor.capture(),
            eq(JsonNode.class));

    assertEquals("sess-1", bodyCaptor.getValue().get("session_id").asText());
    assertEquals("Java", bodyCaptor.getValue().get("arguments").get("query_text").asText());
    assertEquals("ok", result.status());
    assertEquals(
        Integer.valueOf(2),
        ((Map<String, Object>) resultMap.get("future_tool_field")).get("version"));
  }

  @Test
  void invokeRethrowsAndFeedsCircuitOnFailure() {
    AgentServiceProperties properties = properties();
    properties.setCircuitFailureThreshold(1);
    RestTemplate restTemplate = mock(RestTemplate.class);
    ServiceResilience resilience = new ServiceResilience(properties);
    RuntimeToolClient client = new RuntimeToolClientImpl(restTemplate, properties, resilience);

    when(restTemplate.postForObject(any(String.class), any(), eq(JsonNode.class)))
        .thenThrow(new RuntimeException("runtime down"));

    assertThrows(
        RuntimeException.class,
        () -> client.invoke("boss_browser", RuntimeToolArguments.empty(), null, null));
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
            () -> client.invoke("boss_browser", RuntimeToolArguments.empty(), null, null));
    assertTrue(error.getMessage().contains("熔断"));
  }
}
