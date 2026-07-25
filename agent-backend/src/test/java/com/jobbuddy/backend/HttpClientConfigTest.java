package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.config.HttpClientConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * 验证 HttpClientConfig 的核心行为、异常路径与边界条件。
 */
class HttpClientConfigTest {

  private HttpServer server;
  private String baseUrl;

  /**
   * 初始化测试所需依赖与认证上下文。
   *
   * @throws IOException 文件读写失败时抛出
   */
  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/ok", exchange -> respond(exchange, "ok"));
    server.createContext(
        "/token",
        exchange ->
            respond(
                exchange,
                String.valueOf(exchange.getRequestHeaders().getFirst("X-Internal-Service-Token"))));
    server.createContext(
        "/slow",
        exchange -> {
          try {
            Thread.sleep(500);
            respond(exchange, "slow");
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            exchange.close();
          } catch (IOException ignored) {
            exchange.close();
          }
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
   * 验证 HttpClientConfig 的核心业务契约。
   */
  @Test
  void shouldExecuteRequestsWithHttpClient5() {
    RestTemplate restTemplate = createRestTemplate(Duration.ofSeconds(1), Duration.ofSeconds(1));

    assertEquals("ok", restTemplate.getForObject(baseUrl + "/ok", String.class));
  }

  /**
   * 验证 HttpClientConfig 的失败恢复、超时与降级边界。
   */
  @Test
  void shouldApplyConfiguredResponseTimeout() {
    RestTemplate restTemplate = createRestTemplate(Duration.ofSeconds(1), Duration.ofMillis(100));

    assertThrows(
        ResourceAccessException.class,
        () -> restTemplate.getForObject(baseUrl + "/slow", String.class));
  }

  /**
   * 验证 HttpClientConfig 中记忆的失败恢复、超时与降级边界。
   */
  @Test
  void memoryClientShouldUseItsIndependentShortResponseTimeout() {
    AgentServiceProperties properties = properties(Duration.ofSeconds(1), Duration.ofSeconds(2));
    properties.setMemoryConnectTimeout(Duration.ofSeconds(1));
    properties.setMemoryReadTimeout(Duration.ofMillis(100));
    RestTemplate restTemplate = new HttpClientConfig().agentMemoryRestTemplate(properties);

    assertThrows(
        ResourceAccessException.class,
        () -> restTemplate.getForObject(baseUrl + "/slow", String.class));
  }

  /**
   * 验证 HttpClientConfig 的身份认证与会话边界。
   */
  @Test
  void shouldSendInternalServiceTokenWhenConfigured() {
    AgentServiceProperties properties = properties(Duration.ofSeconds(1), Duration.ofSeconds(1));
    properties.setInternalServiceToken("  secret-token  ");
    RestTemplate restTemplate = new HttpClientConfig().restTemplate(properties);

    assertEquals("secret-token", restTemplate.getForObject(baseUrl + "/token", String.class));
  }

  /**
   * 验证 HttpClientConfig 的核心业务契约。
   *
   * @param connectTimeout 连接超时
   * @param readTimeout 读取超时
   * @return HTTP 客户端
   */
  private RestTemplate createRestTemplate(Duration connectTimeout, Duration readTimeout) {
    return new HttpClientConfig().restTemplate(properties(connectTimeout, readTimeout));
  }

  /**
   * 验证配置属性。
   *
   * @param connectTimeout 连接超时
   * @param readTimeout 读取超时
   * @return 测试配置
   */
  private AgentServiceProperties properties(Duration connectTimeout, Duration readTimeout) {
    AgentServiceProperties properties = new AgentServiceProperties();
    properties.setConnectTimeout(connectTimeout);
    properties.setReadTimeout(readTimeout);
    return properties;
  }

  /**
   * 写入模拟 HTTP 响应。
   *
   * @param exchange HTTP 交换函数
   * @param body 请求体
   * @throws IOException 文件读写失败时抛出
   */
  private static void respond(HttpExchange exchange, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
