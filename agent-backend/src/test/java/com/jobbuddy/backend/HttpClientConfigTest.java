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

class HttpClientConfigTest {

  private HttpServer server;
  private String baseUrl;

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

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void shouldExecuteRequestsWithHttpClient5() {
    RestTemplate restTemplate = createRestTemplate(Duration.ofSeconds(1), Duration.ofSeconds(1));

    assertEquals("ok", restTemplate.getForObject(baseUrl + "/ok", String.class));
  }

  @Test
  void shouldApplyConfiguredResponseTimeout() {
    RestTemplate restTemplate = createRestTemplate(Duration.ofSeconds(1), Duration.ofMillis(100));

    assertThrows(
        ResourceAccessException.class,
        () -> restTemplate.getForObject(baseUrl + "/slow", String.class));
  }

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

  @Test
  void shouldSendInternalServiceTokenWhenConfigured() {
    AgentServiceProperties properties = properties(Duration.ofSeconds(1), Duration.ofSeconds(1));
    properties.setInternalServiceToken("  secret-token  ");
    RestTemplate restTemplate = new HttpClientConfig().restTemplate(properties);

    assertEquals("secret-token", restTemplate.getForObject(baseUrl + "/token", String.class));
  }

  private RestTemplate createRestTemplate(Duration connectTimeout, Duration readTimeout) {
    return new HttpClientConfig().restTemplate(properties(connectTimeout, readTimeout));
  }

  private AgentServiceProperties properties(Duration connectTimeout, Duration readTimeout) {
    AgentServiceProperties properties = new AgentServiceProperties();
    properties.setConnectTimeout(connectTimeout);
    properties.setReadTimeout(readTimeout);
    return properties;
  }

  private static void respond(HttpExchange exchange, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
