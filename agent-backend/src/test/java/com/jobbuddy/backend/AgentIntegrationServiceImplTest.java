package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.resilience.ServiceResilience;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeRunRequest;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeRunResult;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeToolArguments;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeToolResult;
import com.jobbuddy.backend.modules.chat.service.impl.AgentIntegrationServiceImpl;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

/**
 * 验证 AgentIntegrationServiceImpl 的核心行为、异常路径与边界条件。
 */
class AgentIntegrationServiceImplTest {

  private HttpServer server;
  private String baseUrl;
  private final JsonCodec jsonCodec = new JsonCodec();
  private final AtomicReference<String> receivedToken = new AtomicReference<String>();
  private final AtomicReference<String> receivedRunBody = new AtomicReference<String>();
  private final AtomicReference<String> receivedStreamBody = new AtomicReference<String>();
  private final AtomicReference<String> receivedToolBody = new AtomicReference<String>();
  private final AtomicReference<String> streamResponseMode = new AtomicReference<String>("done");

  /**
   * 初始化测试所需依赖与认证上下文。
   *
   * @throws IOException 文件读写失败时抛出
   */
  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/agent/runs/stream",
        exchange -> {
          receivedToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Service-Token"));
          receivedStreamBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          String response;
          if ("truncated".equals(streamResponseMode.get())) {
            response =
                "event: processing\ndata: {\"run_id\":\"run-started\",\"trace_id\":\"trace-started\"}\n\n";
          } else if ("empty_error".equals(streamResponseMode.get())) {
            response = "event: error\ndata: {\"run_id\":\"run-error\",\"message\":\"\"}\n\n";
          } else {
            response =
                "event: done\n"
                    + "data: {\"run_id\":\"run-sse\",\"status\":\"ok\","
                    + "\"stop_reason\":\"task_complete\","
                    + "\"tool_results\":[{\"tool_call_id\":\"call-1\","
                    + "\"tool_name\":\"resume_match\",\"success\":true,"
                    + "\"data\":{\"count\":1},\"future_tool_field\":\"kept\"}],"
                    + "\"future_done_field\":{\"version\":2}}\n\n"
                    + "data: [DONE]\n\n";
          }
          byte[] body = response.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/v1/agent/runs",
        exchange -> {
          receivedRunBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] body =
              ("{\"code\":200,\"message\":\"success\",\"data\":{"
                      + "\"run_id\":\"run-http\",\"trace_id\":\"trace-http\","
                      + "\"status\":\"success\",\"stop_reason\":\"task_complete\","
                      + "\"answer\":\"完成\",\"future_run_field\":{\"enabled\":true}}}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/v1/runtime/tools/interview_question_generate/invoke",
        exchange -> {
          receivedToolBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] body =
              ("{\"code\":200,\"message\":\"success\",\"data\":{\"success\":true,"
                      + "\"data\":{\"count\":1},\"future_tool_field\":{\"kept\":true}}}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /**
   * 清理测试创建的资源与上下文。
   */
  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  /**
   * 验证 AgentIntegrationServiceImpl 中运行时的流式生命周期与中断边界。
   */
  @Test
  void runtimeStreamShouldSendInternalServiceTokenWhenConfigured() {
    AgentServiceProperties properties = new AgentServiceProperties();
    properties.setRuntimeUrl(baseUrl);
    properties.setInternalServiceToken("secret-token");
    properties.setStreamConnectTimeout(Duration.ofSeconds(1));
    properties.setStreamReadTimeout(Duration.ofSeconds(1));
    AgentIntegrationServiceImpl service =
        new AgentIntegrationServiceImpl(
            new RestTemplate(), properties, new JsonCodec(), new ServiceResilience(properties));

    Map<String, Object> requestPayload = new LinkedHashMap<String, Object>();
    requestPayload.put("session_id", "session-sse");
    RuntimeRunResult result =
        service.runRuntimeStream(RuntimeRunRequest.fromPayload(requestPayload, jsonCodec), null);
    Map<String, Object> resultMap = result.toMap(jsonCodec);

    assertEquals("secret-token", receivedToken.get());
    assertEquals("ok", result.status());
    assertEquals("task_complete", result.stopReason());
    assertTrue(receivedStreamBody.get().contains("\"session_id\":\"session-sse\""));
    assertEquals(
        Integer.valueOf(2),
        ((Map<String, Object>) resultMap.get("future_done_field")).get("version"));
    List<Map<String, Object>> toolResults =
        (List<Map<String, Object>>) resultMap.get("tool_results");
    assertEquals("kept", toolResults.get(0).get("future_tool_field"));
    assertEquals(
        Integer.valueOf(1), ((Map<String, Object>) toolResults.get(0).get("data")).get("count"));
  }

  /**
   * 验证流已建立但未收到终态时保留 processing 首包中的 run 标识，供 checkpoint 恢复。
   */
  @Test
  void runtimeStreamShouldReturnStartedRunWhenConnectionEndsBeforeDone() {
    streamResponseMode.set("truncated");
    AgentServiceProperties properties = new AgentServiceProperties();
    properties.setRuntimeUrl(baseUrl);
    AgentIntegrationServiceImpl service =
        new AgentIntegrationServiceImpl(
            new RestTemplate(), properties, jsonCodec, new ServiceResilience(properties));

    RuntimeRunResult result =
        service.runRuntimeStream(RuntimeRunRequest.empty().withResumeFromRunId("run-source"), null);
    Map<String, Object> sent = jsonCodec.toMap(receivedStreamBody.get());

    assertEquals("run-source", sent.get("resume_from_run_id"));
    assertEquals("run-started", result.runId());
    assertEquals("trace-started", result.traceId());
    assertTrue(result.error().contains("未返回终态"));
  }

  /**
   * 验证 Runtime 返回空 message 时仍保留明确失败语义，防止上层将其当成空成功并重放任务。
   */
  @Test
  void runtimeStreamShouldNormalizeEmptyErrorMessage() {
    streamResponseMode.set("empty_error");
    AgentServiceProperties properties = new AgentServiceProperties();
    properties.setRuntimeUrl(baseUrl);
    AgentIntegrationServiceImpl service =
        new AgentIntegrationServiceImpl(
            new RestTemplate(), properties, jsonCodec, new ServiceResilience(properties));

    RuntimeRunResult result = service.runRuntimeStream(RuntimeRunRequest.empty(), null);

    assertEquals("run-error", result.runId());
    assertEquals("Agent Runtime 流式执行失败", result.error());
  }

  /**
   * 验证 AgentIntegrationServiceImpl 中运行时的数据转换与协议契约。
   */
  @Test
  void runtimeHttpShouldUseSnakeCaseAndPreserveUnknownResponseFields() {
    AgentServiceProperties properties = new AgentServiceProperties();
    properties.setRuntimeUrl(baseUrl);
    AgentIntegrationServiceImpl service =
        new AgentIntegrationServiceImpl(
            new RestTemplate(), properties, jsonCodec, new ServiceResilience(properties));
    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("trace_id", "trace-request");
    payload.put("session_id", "session-http");
    payload.put("future_request_field", Collections.singletonMap("mode", "keep"));

    RuntimeRunResult result = service.runRuntime(RuntimeRunRequest.fromPayload(payload, jsonCodec));
    Map<String, Object> resultMap = result.toMap(jsonCodec);
    Map<String, Object> sent = jsonCodec.toMap(receivedRunBody.get());

    assertEquals("trace-request", sent.get("trace_id"));
    assertEquals("session-http", sent.get("session_id"));
    assertTrue(sent.containsKey("future_request_field"));
    assertEquals("run-http", result.runId());
    assertEquals("task_complete", result.stopReason());
    assertEquals(
        Boolean.TRUE, ((Map<String, Object>) resultMap.get("future_run_field")).get("enabled"));
  }

  /**
   * 验证 AgentIntegrationServiceImpl 中工具的核心业务契约。
   */
  @Test
  void shouldInvokeNamedRuntimeTool() {
    AgentServiceProperties properties = new AgentServiceProperties();
    properties.setRuntimeUrl(baseUrl);
    AgentIntegrationServiceImpl service =
        new AgentIntegrationServiceImpl(
            new RestTemplate(), properties, new JsonCodec(), new ServiceResilience(properties));
    Map<String, Object> arguments = new LinkedHashMap<String, Object>();
    arguments.put("topic", "动态规划");

    RuntimeToolResult result =
        service.invokeRuntimeTool(
            "interview_question_generate", RuntimeToolArguments.fromMap(arguments, jsonCodec));
    Map<String, Object> resultMap = result.toMap(jsonCodec);
    Map<String, Object> body = jsonCodec.toMap(receivedToolBody.get());
    Map<String, Object> sentArguments = (Map<String, Object>) body.get("arguments");

    assertEquals("动态规划", sentArguments.get("topic"));
    assertEquals(Boolean.TRUE, result.success());
    assertEquals(Integer.valueOf(1), ((Map) resultMap.get("data")).get("count"));
    assertEquals(
        Boolean.TRUE, ((Map<String, Object>) resultMap.get("future_tool_field")).get("kept"));
  }
}
