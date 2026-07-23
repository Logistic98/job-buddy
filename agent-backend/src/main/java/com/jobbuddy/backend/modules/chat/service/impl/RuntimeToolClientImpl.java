package com.jobbuddy.backend.modules.chat.service.impl;

import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.resilience.ServiceResilience;
import com.jobbuddy.backend.modules.chat.service.RuntimeToolClient;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
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
  public Map<String, Object> invoke(
      String toolName, Map<String, Object> arguments, String sessionId, String workspaceDir) {
    if (resilience.isOpen(SERVICE)) {
      throw new RuntimeException("Runtime 工具服务熔断中，暂时不可用: " + toolName);
    }
    Map<String, Object> body = new LinkedHashMap<String, Object>();
    body.put("arguments", arguments == null ? Collections.emptyMap() : arguments);
    if (sessionId != null && !sessionId.isEmpty()) body.put("session_id", sessionId);
    if (workspaceDir != null && !workspaceDir.isEmpty()) body.put("workspace_dir", workspaceDir);

    String url = properties.getRuntimeUrl() + "/v1/runtime/tools/" + toolName + "/invoke";
    try {
      Map response = postForTool(url, body, toolName, true);
      if (response == null) throw new RuntimeException("Runtime 工具返回空响应: " + toolName);
      Object data = response.get("data");
      if (!(data instanceof Map)) throw new RuntimeException("Runtime 工具响应缺少 data: " + toolName);
      resilience.recordSuccess(SERVICE);
      return (Map<String, Object>) data;
    } catch (RuntimeException e) {
      resilience.recordFailure(SERVICE);
      throw e;
    }
  }

  private Map postForTool(
      String url, Map<String, Object> body, String toolName, boolean retryOnMissingTool) {
    try {
      return restTemplate.postForObject(url, body, Map.class);
    } catch (HttpClientErrorException.NotFound e) {
      String response = e.getResponseBodyAsString();
      if (retryOnMissingTool && response != null && response.contains("工具未找到")) {
        try {
          restTemplate.postForObject(
              properties.getRuntimeUrl() + "/v1/runtime/tools/reload-builtins",
              Collections.emptyMap(),
              Map.class);
          return postForTool(url, body, toolName, false);
        } catch (RuntimeException ignored) {
          // 保留原始异常，便于定位缺失工具。
        }
      }
      throw new RuntimeException("Runtime 工具不存在或未启用: " + toolName + "; " + response, e);
    }
  }
}
