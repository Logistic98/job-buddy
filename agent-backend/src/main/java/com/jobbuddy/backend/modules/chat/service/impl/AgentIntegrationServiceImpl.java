package com.jobbuddy.backend.modules.chat.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.resilience.ServiceResilience;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeRunRequest;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeRunResult;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeToolArguments;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeToolResult;
import com.jobbuddy.backend.modules.chat.service.AgentIntegrationService;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * 实现 Backend 到 Agent Runtime 的有界 HTTP 与 SSE 调用。
 *
 * <p>内部身份随请求透传，传输故障由公共弹性策略分类；仅 Runtime 明确为可选时允许返回空结果。
 */
@Service
public class AgentIntegrationServiceImpl implements AgentIntegrationService {
  private static final Logger log = LoggerFactory.getLogger(AgentIntegrationServiceImpl.class);
  private final RestTemplate restTemplate;
  private final AgentServiceProperties properties;
  private final JsonCodec jsonCodec;
  private final ServiceResilience resilience;

  /**
   * 创建 Agent 集成服务实例。
   *
   * @param restTemplate HTTP 请求客户端
   * @param properties 配置属性
   * @param jsonCodec JSON 编解码器
   * @param resilience 弹性策略
   */
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

  /**
   * 执行 Runtime。
   *
   * @param request 请求对象
   * @return 执行后的运行时
   */
  public RuntimeRunResult runRuntime(RuntimeRunRequest request) {
    String baseUrl = runtimeBaseUrl();
    if (baseUrl.isEmpty()) return RuntimeRunResult.empty();
    RuntimeRunRequest runtimeRequest = request == null ? RuntimeRunRequest.empty() : request;
    return postRuntimeRun(baseUrl + "/v1/agent/runs", runtimeRequest);
  }

  /**
   * 调用 Runtime 工具。
   *
   * @param toolName 工具名称
   * @param arguments 工具参数
   * @return Runtime 工具调用结果
   */
  public RuntimeToolResult invokeRuntimeTool(String toolName, RuntimeToolArguments arguments) {
    String baseUrl = runtimeBaseUrl();
    if (baseUrl.isEmpty()) return RuntimeToolResult.empty();
    String normalizedName = toolName == null ? "" : toolName.trim();
    if (!normalizedName.matches("[a-z][a-z0-9_]{1,63}")) {
      throw new IllegalArgumentException("Runtime 工具名称格式不正确");
    }
    ObjectNode request = JsonNodeFactory.instance.objectNode();
    request.set(
        "arguments",
        arguments == null ? JsonNodeFactory.instance.objectNode() : arguments.toJson());
    return postRuntimeTool(baseUrl + "/v1/runtime/tools/" + normalizedName + "/invoke", request);
  }

  /**
   * 执行 Runtime 流式响应。
   *
   * @param request 请求对象
   * @param onToken 令牌回调函数
   * @return 执行后的运行时流式
   */
  public RuntimeRunResult runRuntimeStream(RuntimeRunRequest request, Consumer<String> onToken) {
    return runRuntimeStream(request, onToken, null);
  }

  /**
   * 执行 Runtime 流式响应。
   *
   * @param request 请求对象
   * @param onToken 令牌回调函数
   * @param onReasoning 推理内容回调
   * @return 执行后的运行时流式
   */
  public RuntimeRunResult runRuntimeStream(
      RuntimeRunRequest request, Consumer<String> onToken, Consumer<String> onReasoning) {
    String baseUrl = runtimeBaseUrl();
    // 未配置服务或熔断开启时直接降级，避免继续建立流式连接。
    if (baseUrl.isEmpty()) return RuntimeRunResult.empty();
    String url = baseUrl + "/v1/agent/runs/stream";
    if (resilience.isOpen("agent-runtime-stream")) {
      log.warn("Agent Runtime 流式熔断开启中，直接降级：url={}", url);
      return RuntimeRunResult.empty();
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
          jsonCodec.toJson(request == null ? RuntimeRunRequest.empty().toJson() : request.toJson());
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
        return RuntimeRunResult.empty();
      }
      // SSE 逐帧区分答案、推理、终态和错误事件。
      RuntimeRunResult doneData = RuntimeRunResult.empty();
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
            JsonNode payload = jsonCodec.readTree(data);
            if ("token".equals(currentEvent)) {
              JsonNode text = payload.get("text");
              if (text != null && !text.isNull() && onToken != null) onToken.accept(text.asText());
            } else if ("reasoning".equals(currentEvent)) {
              JsonNode text = payload.get("text");
              if (text != null && !text.isNull() && onReasoning != null)
                onReasoning.accept(text.asText());
            } else if ("done".equals(currentEvent)) {
              doneData = RuntimeRunResult.fromJson(payload);
            } else if ("error".equals(currentEvent)) {
              RuntimeRunResult err =
                  RuntimeRunResult.fromJson(payload)
                      .withError(
                          payload.has("message") && !payload.get("message").isNull()
                              ? payload.get("message").asText()
                              : null);
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
      return RuntimeRunResult.empty();
    } finally {
      if (conn != null) conn.disconnect();
    }
  }

  /**
   * 获取运行时服务地址。
   *
   * @return 运行时服务地址
   */
  private String runtimeBaseUrl() {
    return properties.resolvedRuntimeUrl();
  }

  /**
   * 构建运行时执行请求。
   *
   * @param url 请求地址
   * @param request 请求对象
   * @return 运行时执行响应
   */
  private RuntimeRunResult postRuntimeRun(final String url, final RuntimeRunRequest request) {
    return resilience.call(
        "agent-runtime",
        new java.util.function.Supplier<RuntimeRunResult>() {
          /**
           * 按标识读取数据。
           *
           * @return 查询结果
           */
          public RuntimeRunResult get() {
            JsonNode response = restTemplate.postForObject(url, request.toJson(), JsonNode.class);
            return RuntimeRunResult.fromJson(responseDataOrEnvelope(response));
          }
        },
        RuntimeRunResult.empty(),
        false);
  }

  /**
   * 构建运行时工具请求。
   *
   * @param url 请求地址
   * @param request 请求对象
   * @return 运行时工具响应
   */
  private RuntimeToolResult postRuntimeTool(final String url, final ObjectNode request) {
    return resilience.call(
        "agent-runtime",
        new java.util.function.Supplier<RuntimeToolResult>() {
          /**
           * 按标识读取数据。
           *
           * @return 查询结果
           */
          public RuntimeToolResult get() {
            JsonNode response = restTemplate.postForObject(url, request, JsonNode.class);
            return RuntimeToolResult.fromJson(responseDataOrEnvelope(response));
          }
        },
        RuntimeToolResult.empty(),
        false);
  }

  /**
   * 提取响应数据或完整响应体。
   *
   * @param response 响应对象
   * @return 响应数据或完整响应体
   */
  private JsonNode responseDataOrEnvelope(JsonNode response) {
    if (response == null || !response.isObject()) return JsonNodeFactory.instance.objectNode();
    JsonNode data = response.get("data");
    return data != null && data.isObject() ? data : response;
  }
}
