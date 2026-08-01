package com.jobbuddy.backend.modules.system.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.system.client.AgentMemoryClient;
import com.jobbuddy.backend.modules.system.mapper.SystemSettingsMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 验证 SystemSettingsServiceHealthMonitor 的核心行为、异常路径与边界条件。
 */
class SystemSettingsServiceHealthMonitorTest {
  private static final JsonCodec JSON = new JsonCodec();

  private HttpServer server;
  private String baseUrl;

  /**
   * 启动可控的下游健康端点。
   *
   * @throws IOException 端口绑定失败时抛出
   */
  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/degraded/health",
        exchange ->
            respond(
                exchange,
                200,
                """
                {"code":200,"message":"success","data":{"status":"DEGRADED","reason":"gateway unavailable"}}
                """));
    server.createContext(
        "/up/health",
        exchange ->
            respond(
                exchange,
                200,
                """
                {"code":200,"message":"success","data":{"status":"UP"}}
                """));
    server.createContext(
        "/healthy/health", exchange -> respond(exchange, 200, "{\"status\":\"healthy\"}"));
    server.createContext("/legacy/health", exchange -> respond(exchange, 200, "ok"));
    server.createContext(
        "/missing-status/health",
        exchange -> respond(exchange, 200, "{\"code\":200,\"message\":\"success\",\"data\":{}}"));
    server.createContext(
        "/starting/health",
        exchange ->
            respond(
                exchange,
                200,
                "{\"code\":200,\"message\":\"success\",\"data\":{\"status\":\"STARTING\"}}"));
    server.createContext(
        "/unavailable/health",
        exchange ->
            respond(
                exchange,
                503,
                "{\"code\":503,\"message\":\"unavailable\",\"data\":{\"status\":\"DOWN\"}}"));
    server.createContext(
        "/slow-sandbox/ready",
        exchange ->
            respondAfter(
                exchange,
                1700,
                200,
                "{\"code\":200,\"message\":\"success\",\"data\":{\"status\":\"UP\"}}"));
    server.createContext("/invalid-sandbox/ready", exchange -> respond(exchange, 200, "ok"));
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /**
   * 停止测试下游服务。
   */
  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  /**
   * 验证读取设置时保留服务健康历史。
   */
  @Test
  void keepsHealthHistoryAcrossSettingsReads() {
    SystemSettingsMapper mapper = mock(SystemSettingsMapper.class);
    when(mapper.findSettingJson("global", "settings")).thenReturn(null);
    when(mapper.listBlacklistItems()).thenReturn(List.of());
    SystemSettingsServiceImpl service =
        new SystemSettingsServiceImpl(
            emptyServiceProperties(),
            new JobBuddyProperties(),
            mapper,
            mock(AgentMemoryClient.class));

    service.refreshServiceStatuses();
    Map<String, Object> secondRefresh = JSON.toMap(service.refreshServiceStatuses());

    assertEquals(2, historySize(secondRefresh, "runtime"));
    assertEquals(2, historySize(secondRefresh, "sandbox"));
    Map<String, Object> settings = JSON.toMap(service.getSettings());
    assertEquals(2, historySize(statuses(settings), "runtime"));
  }

  /**
   * 验证 SystemSettingsServiceHealthMonitor 的数量、长度与分页边界。
   */
  @Test
  void scheduledSamplesAreLimitedToRecentHistory() {
    SystemSettingsServiceImpl service =
        new SystemSettingsServiceImpl(
            emptyServiceProperties(),
            new JobBuddyProperties(),
            mock(SystemSettingsMapper.class),
            mock(AgentMemoryClient.class));

    Map<String, Object> statuses = null;
    for (int index = 0; index < 65; index++)
      statuses = JSON.toMap(service.refreshServiceStatuses());

    assertEquals(60, historySize(statuses, "runtime"));
  }

  /**
   * 验证 Sandbox 使用真实 Runtime readiness，其他服务仍使用进程健康端点。
   */
  @Test
  void usesRuntimeReadinessForSandboxOnly() {
    assertEquals(
        "http://agent-sandbox:8061/ready",
        ServiceHealthMonitor.healthUrl("sandbox", "http://agent-sandbox:8061/"));
    assertEquals(
        "http://agent-runtime:8010/health",
        ServiceHealthMonitor.healthUrl("runtime", "http://agent-runtime:8010"));
  }

  /**
   * 验证仍在真实 srt readiness 预算内的慢响应不会被 1.5 秒通用健康超时误判。
   */
  @Test
  void keepsSlowSandboxReadinessWithinDedicatedBudgetRunning() {
    AgentServiceProperties properties = emptyServiceProperties();
    properties.setSandboxUrl(baseUrl + "/slow-sandbox");
    ServiceHealthMonitor monitor = new ServiceHealthMonitor(properties, new JobBuddyProperties());

    Map<String, Object> sandbox = serviceStatus(monitor, "sandbox");

    assertEquals("running", sandbox.get("status"));
    assertTrue((Boolean) sandbox.get("success"));
    assertEquals("运行中", sandbox.get("message"));
  }

  /**
   * 验证 Sandbox readiness 必须返回明确业务状态，不能沿用旧式健康正文的宽松兼容语义。
   */
  @Test
  void requiresExplicitBusinessStatusForSandboxReadiness() {
    AgentServiceProperties properties = emptyServiceProperties();
    properties.setSandboxUrl(baseUrl + "/invalid-sandbox");
    ServiceHealthMonitor monitor = new ServiceHealthMonitor(properties, new JobBuddyProperties());

    Map<String, Object> sandbox = serviceStatus(monitor, "sandbox");

    assertEquals("down", sandbox.get("status"));
    assertFalse((Boolean) sandbox.get("success"));
    assertEquals("健康检查失败，Sandbox readiness 未返回状态", sandbox.get("message"));
  }

  /**
   * 验证 Sandbox readiness 超时配置存在硬上限，避免串行监测因误配置被长期占用。
   */
  @Test
  void capsSandboxReadinessTimeoutToKeepMonitorBounded() {
    AgentServiceProperties properties = emptyServiceProperties();
    properties.setSandboxHealthReadTimeout(Duration.ofHours(1));
    ServiceHealthMonitor monitor = new ServiceHealthMonitor(properties, new JobBuddyProperties());

    assertEquals(35_000, monitor.healthReadTimeoutMillis("sandbox"));
    assertEquals(1500, monitor.healthReadTimeoutMillis("runtime"));
  }

  /**
   * 验证 HTTP 200 中的业务降级状态不会被误计为运行成功。
   */
  @Test
  void mapsUnifiedDegradedHealthToUnsuccessfulDegradedStatus() {
    Map<String, Object> memory = memoryStatus("/degraded");

    assertEquals("degraded", memory.get("status"));
    assertFalse((Boolean) memory.get("success"));
    assertEquals("运行降级：gateway unavailable", memory.get("message"));
  }

  /**
   * 验证健康状态和旧式健康正文都保留 HTTP 2xx 兼容语义。
   */
  @Test
  void keepsHealthyAndLegacyTwoHundredResponsesRunning() {
    for (String path : List.of("/up", "/healthy", "/legacy", "/missing-status")) {
      Map<String, Object> memory = memoryStatus(path);
      assertEquals("running", memory.get("status"));
      assertTrue((Boolean) memory.get("success"));
      assertEquals("运行中", memory.get("message"));
    }
  }

  /**
   * 验证非 2xx 健康响应始终标记为不可用。
   */
  @Test
  void mapsNonTwoHundredResponseToDown() {
    Map<String, Object> memory = memoryStatus("/unavailable");

    assertEquals("down", memory.get("status"));
    assertFalse((Boolean) memory.get("success"));
    assertEquals("健康检查失败，HTTP 503", memory.get("message"));
  }

  /**
   * 验证未知但明确返回的业务状态不会被误计为运行成功。
   */
  @Test
  void mapsUnsupportedBusinessStatusToUnknown() {
    Map<String, Object> memory = memoryStatus("/starting");

    assertEquals("unknown", memory.get("status"));
    assertFalse((Boolean) memory.get("success"));
    assertEquals("未知业务状态 STARTING", memory.get("message"));
  }

  /**
   * 验证空值服务配置属性。
   *
   * @return emptyService 配置属性
   */
  private AgentServiceProperties emptyServiceProperties() {
    AgentServiceProperties properties = new AgentServiceProperties();
    properties.setIntentUrl("");
    properties.setRuntimeUrl("");
    properties.setMemoryUrl("");
    properties.setToolUrl("");
    properties.setEvalUrl("");
    properties.setSandboxUrl("");
    return properties;
  }

  /**
   * 探测指定模拟健康端点并读取 Memory 状态。
   *
   * @param path 模拟端点前缀
   * @return Memory 服务状态
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> memoryStatus(String path) {
    AgentServiceProperties properties = emptyServiceProperties();
    properties.setMemoryUrl(baseUrl + path);
    ServiceHealthMonitor monitor = new ServiceHealthMonitor(properties, new JobBuddyProperties());
    return serviceStatus(monitor, "memory");
  }

  /**
   * 读取指定服务的单次监测状态。
   *
   * @param monitor 服务监视器
   * @param serviceId 服务标识
   * @return 单个服务状态
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> serviceStatus(ServiceHealthMonitor monitor, String serviceId) {
    Map<String, Object> statuses = JSON.toMap(monitor.refresh().statuses());
    return (Map<String, Object>) statuses.get(serviceId);
  }

  /**
   * 验证状态列表。
   *
   * @param settings 设置
   * @return 服务状态列表
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> statuses(Map<String, Object> settings) {
    return (Map<String, Object>) settings.get("serviceStatuses");
  }

  /**
   * 读取指定服务的健康历史长度。
   *
   * @param statuses 状态列表
   * @param serviceId 服务标识
   * @return history 大小
   */
  @SuppressWarnings("unchecked")
  private int historySize(Map<String, Object> statuses, String serviceId) {
    Map<String, Object> status = (Map<String, Object>) statuses.get(serviceId);
    return ((List<Map<String, Object>>) status.get("history")).size();
  }

  /**
   * 写入模拟健康响应。
   *
   * @param exchange HTTP 交换
   * @param statusCode HTTP 状态码
   * @param body 响应正文
   * @throws IOException 写入失败时抛出
   */
  private static void respond(HttpExchange exchange, int statusCode, String body)
      throws IOException {
    byte[] bytes = body.trim().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
    exchange.sendResponseHeaders(statusCode, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  /**
   * 延迟返回健康响应，用于覆盖慢于通用轻量探针但仍在 Sandbox readiness 预算内的情况。
   *
   * @param exchange HTTP 交换
   * @param delayMillis 延迟毫秒数
   * @param statusCode HTTP 状态码
   * @param body 响应正文
   * @throws IOException 写入失败时抛出
   */
  private static void respondAfter(
      HttpExchange exchange, long delayMillis, int statusCode, String body) throws IOException {
    try {
      Thread.sleep(delayMillis);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      exchange.close();
      return;
    }
    respond(exchange, statusCode, body);
  }
}
