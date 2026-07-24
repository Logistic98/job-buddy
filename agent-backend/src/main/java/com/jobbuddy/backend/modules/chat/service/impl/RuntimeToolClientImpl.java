package com.jobbuddy.backend.modules.chat.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.resilience.ServiceResilience;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeToolArguments;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeToolResult;
import com.jobbuddy.backend.modules.chat.service.RuntimeToolClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@Service
public class RuntimeToolClientImpl implements RuntimeToolClient {

  private static final String SERVICE = "agent-runtime-tool";

  private final RestTemplate restTemplate;
  private final AgentServiceProperties properties;
  private final ServiceResilience resilience;

  public RuntimeToolClientImpl(
      RestTemplate restTemplate, AgentServiceProperties properties, ServiceResilience resilience) {
    this.restTemplate = restTemplate;
    this.properties = properties;
    this.resilience = resilience;
  }

  @Override
  public RuntimeToolResult invoke(
      String toolName, RuntimeToolArguments arguments, String sessionId, String workspaceDir) {
    if (resilience.isOpen(SERVICE)) {
      throw new RuntimeException("Runtime 工具服务熔断中，暂时不可用: " + toolName);
    }
    ObjectNode body = JsonNodeFactory.instance.objectNode();
    body.set(
        "arguments",
        arguments == null ? JsonNodeFactory.instance.objectNode() : arguments.toJson());
    if (sessionId != null && !sessionId.isEmpty()) body.put("session_id", sessionId);
    if (workspaceDir != null && !workspaceDir.isEmpty()) body.put("workspace_dir", workspaceDir);

    String url = properties.getRuntimeUrl() + "/v1/runtime/tools/" + toolName + "/invoke";
    try {
      JsonNode response = postForTool(url, body, toolName, true);
      if (response == null) throw new RuntimeException("Runtime 工具返回空响应: " + toolName);
      JsonNode data = response.get("data");
      if (data == null || !data.isObject())
        throw new RuntimeException("Runtime 工具响应缺少 data: " + toolName);
      resilience.recordSuccess(SERVICE);
      return RuntimeToolResult.fromJson(data);
    } catch (RuntimeException e) {
      resilience.recordFailure(SERVICE);
      throw e;
    }
  }

  private JsonNode postForTool(
      String url, ObjectNode body, String toolName, boolean retryOnMissingTool) {
    try {
      return restTemplate.postForObject(url, body, JsonNode.class);
    } catch (HttpClientErrorException.NotFound e) {
      String response = e.getResponseBodyAsString();
      if (retryOnMissingTool && response != null && response.contains("工具未找到")) {
        try {
          restTemplate.postForObject(
              properties.getRuntimeUrl() + "/v1/runtime/tools/reload-builtins",
              JsonNodeFactory.instance.objectNode(),
              JsonNode.class);
          return postForTool(url, body, toolName, false);
        } catch (RuntimeException ignored) {
          // 保留原始异常，便于定位缺失工具。
        }
      }
      throw new RuntimeException("Runtime 工具不存在或未启用: " + toolName + "; " + response, e);
    }
  }
}
