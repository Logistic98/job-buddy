package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.resilience.ServiceResilience;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.chat.service.impl.AgentIntegrationServiceImpl;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

class AgentIntegrationServiceImplTest {

  private HttpServer server;
  private String baseUrl;
  private final AtomicReference<String> receivedToken = new AtomicReference<String>();

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/agent/runs/stream",
        exchange -> {
          receivedToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Service-Token"));
          byte[] body =
              ("event: done\n" + "data: {\"status\":\"ok\"}\n\n").getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/v1/runtime/tools/interview_question_generate/invoke",
        exchange -> {
          byte[] body =
              "{\"code\":200,\"message\":\"success\",\"data\":{\"success\":true,\"data\":{\"count\":1}}}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

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

    Map<String, Object> result =
        service.runRuntimeStream(Collections.<String, Object>emptyMap(), null);

    assertEquals("secret-token", receivedToken.get());
    assertEquals("ok", result.get("status"));
  }

  @Test
  void shouldInvokeNamedRuntimeTool() {
    AgentServiceProperties properties = new AgentServiceProperties();
    properties.setRuntimeUrl(baseUrl);
    AgentIntegrationServiceImpl service =
        new AgentIntegrationServiceImpl(
            new RestTemplate(), properties, new JsonCodec(), new ServiceResilience(properties));
    Map<String, Object> arguments = new LinkedHashMap<String, Object>();
    arguments.put("topic", "动态规划");

    Map<String, Object> result =
        service.invokeRuntimeTool("interview_question_generate", arguments);

    assertEquals(Boolean.TRUE, result.get("success"));
    assertEquals(Integer.valueOf(1), ((Map) result.get("data")).get("count"));
  }
}
