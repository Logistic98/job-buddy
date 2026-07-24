package com.jobbuddy.backend.modules.chat.service.impl;

import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.resilience.ServiceResilience;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.chat.service.AgentIntegrationService;
import com.jobbuddy.backend.modules.chat.vo.TraceStep;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class AgentIntegrationServiceImpl implements AgentIntegrationService {
  private static final Logger log = LoggerFactory.getLogger(AgentIntegrationServiceImpl.class);
  private final RestTemplate restTemplate;
  private final AgentServiceProperties properties;
  private final JsonCodec jsonCodec;
  private final ServiceResilience resilience;

  public AgentIntegrationServiceImpl(
      RestTemplate restTemplate,
      AgentServiceProperties properties,
      JsonCodec jsonCodec,
      ServiceResilience resilience) {
    this.restTemplate = restTemplate;
    this.properties = properties;
    this.jsonCodec = jsonCodec;
    this.resilience = resilience;
  }

  public List<Map<String, Object>> listTools() {
    final String url = properties.getToolUrl() + "/v1/tools";
    return resilience.call(
        "agent-tool",
        new java.util.function.Supplier<List<Map<String, Object>>>() {
          public List<Map<String, Object>> get() {
            Map response = restTemplate.getForObject(url, Map.class);
            Object data = response == null ? null : response.get("data");
            return data instanceof List
                ? (List<Map<String, Object>>) data
                : Collections.<Map<String, Object>>emptyList();
          }
        },
        Collections.<Map<String, Object>>emptyList(),
        true);
  }

  public List<Map<String, Object>> searchMemory(final String query, final String scope) {
    final String url =
        properties.getMemoryUrl()
            + "/v1/memories/search?q="
            + encode(query)
            + "&scope="
            + encode(scope);
    return resilience.call(
        "agent-memory",
        new java.util.function.Supplier<List<Map<String, Object>>>() {
          public List<Map<String, Object>> get() {
            Map response = restTemplate.getForObject(url, Map.class);
            Object data = response == null ? null : response.get("data");
            return data instanceof List
                ? (List<Map<String, Object>>) data
                : Collections.<Map<String, Object>>emptyList();
          }
        },
        Collections.<Map<String, Object>>emptyList(),
        true);
  }

  public void writeMemory(final String scope, final String content) {
    resilience.call(
        "agent-memory-write",
        new java.util.function.Supplier<Void>() {
          public Void get() {
            Map<String, String> request = new HashMap<String, String>();
            request.put("scope", scope);
            request.put("content", content);
            restTemplate.postForObject(
                properties.getMemoryUrl() + "/v1/memories", request, Map.class);
            return null;
          }
        },
        null,
        false);
  }

  public Map<String, Object> evalTrace(final List<TraceStep> trace) {
    return resilience.call(
        "agent-eval",
        new java.util.function.Supplier<Map<String, Object>>() {
          public Map<String, Object> get() {
            Map<String, Object> request = new HashMap<String, Object>();
            request.put("trace", trace);
            Map response =
                restTemplate.postForObject(
                    properties.getEvalUrl() + "/v1/eval/trace", request, Map.class);
            Object data = response == null ? null : response.get("data");
            return data instanceof Map
                ? (Map<String, Object>) data
                : Collections.<String, Object>emptyMap();
          }
        },
        Collections.<String, Object>emptyMap(),
        true);
  }

  public Map<String, Object> runRuntime(Map<String, Object> request) {
    String baseUrl = runtimeBaseUrl();
    if (baseUrl.isEmpty()) return Collections.emptyMap();
    Map<String, Object> runtimeRequest = request == null ? new HashMap<String, Object>() : request;
    return postRuntime(baseUrl + "/v1/agent/runs", runtimeRequest);
  }

  public Map<String, Object> invokeRuntimeTool(String toolName, Map<String, Object> arguments) {
    String baseUrl = runtimeBaseUrl();
    if (baseUrl.isEmpty()) return Collections.emptyMap();
    String normalizedName = toolName == null ? "" : toolName.trim();
    if (!normalizedName.matches("[a-z][a-z0-9_]{1,63}")) {
      throw new IllegalArgumentException("Runtime 工具名称格式不正确");
    }
    Map<String, Object> request = new HashMap<String, Object>();
    request.put(
        "arguments", arguments == null ? Collections.<String, Object>emptyMap() : arguments);
    return postRuntime(baseUrl + "/v1/runtime/tools/" + normalizedName + "/invoke", request);
  }

  public Map<String, Object> runRuntimeStream(
      Map<String, Object> request, Consumer<String> onToken) {
    return runRuntimeStream(request, onToken, null);
  }

  public Map<String, Object> runRuntimeStream(
      Map<String, Object> request, Consumer<String> onToken, Consumer<String> onReasoning) {
    String baseUrl = runtimeBaseUrl();
    if (baseUrl.isEmpty()) return Collections.emptyMap();
    String url = baseUrl + "/v1/agent/runs/stream";
    if (resilience.isOpen("agent-runtime-stream")) {
      log.warn("Agent Runtime 流式熔断开启中，直接降级：url={}", url);
      return Collections.emptyMap();
    }
    HttpURLConnection conn = null;
    try {
      conn = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-Type", "application/json");
      conn.setRequestProperty("Accept", "text/event-stream");
      String internalServiceToken = properties.resolvedInternalServiceToken();
      if (!internalServiceToken.isEmpty()) {
        conn.setRequestProperty("X-Internal-Service-Token", internalServiceToken);
      }
      conn.setDoOutput(true);
      conn.setConnectTimeout((int) properties.getStreamConnectTimeout().toMillis());
      conn.setReadTimeout((int) properties.getStreamReadTimeout().toMillis());
      String bodyJson =
          jsonCodec.toJson(request == null ? Collections.<String, Object>emptyMap() : request);
      byte[] body = bodyJson.getBytes(StandardCharsets.UTF_8);
      OutputStream os = conn.getOutputStream();
      try {
        os.write(body);
      } finally {
        os.close();
      }
      int code = conn.getResponseCode();
      if (code != 200) {
        String errBody = "";
        try {
          java.io.InputStream es = conn.getErrorStream();
          if (es != null) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = es.read(buf)) != -1) bos.write(buf, 0, n);
            errBody = new String(bos.toByteArray(), StandardCharsets.UTF_8);
          }
        } catch (Exception e) {
          // 读取错误响应体失败不影响下面的失败上报，仅 debug 留痕。
          log.debug("读取 Agent Runtime 错误响应体失败: {}", e.getMessage());
        }
        log.warn("Agent Runtime 流式调用失败：url={}, code={}, body={}", url, code, errBody);
        resilience.recordFailure("agent-runtime-stream");
        return Collections.emptyMap();
      }
      Map<String, Object> doneData = Collections.emptyMap();
      String currentEvent = "message";
      BufferedReader reader =
          new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
      try {
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.isEmpty()) {
            currentEvent = "message";
            continue;
          }
          if (line.startsWith("event:")) {
            currentEvent = line.substring("event:".length()).trim();
            continue;
          }
          if (line.startsWith("data:")) {
            String data = line.substring("data:".length()).trim();
            if (data.isEmpty() || "[DONE]".equals(data)) continue;
            Map<String, Object> payload = jsonCodec.toMap(data);
            if ("token".equals(currentEvent)) {
              Object text = payload.get("text");
              if (text != null && onToken != null) onToken.accept(String.valueOf(text));
            } else if ("reasoning".equals(currentEvent)) {
              Object text = payload.get("text");
              if (text != null && onReasoning != null) onReasoning.accept(String.valueOf(text));
            } else if ("done".equals(currentEvent)) {
              doneData = payload;
            } else if ("error".equals(currentEvent)) {
              Map<String, Object> err = new HashMap<String, Object>(payload);
              err.put("error", payload.get("message"));
              resilience.recordSuccess("agent-runtime-stream");
              return err;
            }
          }
        }
      } finally {
        reader.close();
      }
      resilience.recordSuccess("agent-runtime-stream");
      return doneData;
    } catch (Exception e) {
      log.warn("Agent Runtime 流式调用异常：url={}", url, e);
      resilience.recordFailure("agent-runtime-stream");
      return Collections.emptyMap();
    } finally {
      if (conn != null) conn.disconnect();
    }
  }

  private String runtimeBaseUrl() {
    return properties.resolvedRuntimeUrl();
  }

  private Map<String, Object> postRuntime(final String url, final Map<String, Object> request) {
    return resilience.call(
        "agent-runtime",
        new java.util.function.Supplier<Map<String, Object>>() {
          public Map<String, Object> get() {
            Map response = restTemplate.postForObject(url, request, Map.class);
            Object data = response == null ? null : response.get("data");
            if (data instanceof Map) return (Map<String, Object>) data;
            return response == null
                ? Collections.<String, Object>emptyMap()
                : new HashMap<String, Object>(response);
          }
        },
        Collections.<String, Object>emptyMap(),
        false);
  }

  private String encode(String value) {
    try {
      return java.net.URLEncoder.encode(value == null ? "" : value, "UTF-8");
    } catch (Exception ignored) {
      return "";
    }
  }
}
