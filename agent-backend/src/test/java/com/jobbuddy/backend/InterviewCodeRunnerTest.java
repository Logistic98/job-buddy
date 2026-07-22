package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.resilience.ServiceResilience;
import com.jobbuddy.backend.modules.interview.service.impl.InterviewCodeRunner;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestTemplate;

class InterviewCodeRunnerTest {
  @Test
  void shouldDelegateCodeExecutionToAgentSandbox() {
    ObjectMapper objectMapper = new ObjectMapper();
    RestTemplate restTemplate = mock(RestTemplate.class);
    AgentServiceProperties properties = new AgentServiceProperties();
    properties.setSandboxUrl("http://sandbox.local:8000/");

    Map<String, Object> sandboxResponse = new LinkedHashMap<String, Object>();
    sandboxResponse.put("ok", Boolean.TRUE);
    sandboxResponse.put("returncode", Integer.valueOf(0));
    sandboxResponse.put(
        "stdout", "{\"passed\":true,\"rows\":[{\"name\":\"示例\",\"passed\":true}]}\n");
    sandboxResponse.put("stderr", "");
    when(restTemplate.postForObject(
            eq("http://sandbox.local:8000/v1/code-file"), any(Map.class), eq(Map.class)))
        .thenReturn(sandboxResponse);

    InterviewCodeRunner runner =
        new InterviewCodeRunner(
            objectMapper, restTemplate, properties, new ServiceResilience(properties));
    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("language", "python");
    payload.put(
        "source", "class Solution:\n    def solution(self, nums):\n        return sum(nums)\n");
    payload.put("functionName", "solution");
    Map<String, Object> test = new LinkedHashMap<String, Object>();
    test.put("name", "示例");
    test.put("args", Arrays.<Object>asList(Arrays.asList(1, 2, 3)));
    test.put("expected", Integer.valueOf(6));
    payload.put("tests", Arrays.asList(test));

    Map<String, Object> result = runner.run(payload);

    assertEquals(Boolean.TRUE, result.get("passed"));
    ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
    verify(restTemplate)
        .postForObject(
            eq("http://sandbox.local:8000/v1/code-file"), bodyCaptor.capture(), eq(Map.class));
    Map body = bodyCaptor.getValue();
    assertEquals(".py", body.get("suffix"));
    assertEquals("python3", body.get("interpreter"));
    String orchestrator = String.valueOf(body.get("code"));
    assertTrue(orchestrator.contains("CODE_B64"));
    assertTrue(orchestrator.contains("dir=os.getcwd()"));
    String childCode = decodeEmbeddedChildCode(orchestrator);
    assertTrue(childCode.contains("globals().get(\"Solution\")"));
    assertTrue(childCode.contains("has_expected = \"expected\" in test"));
    assertTrue(body.containsKey("policy"));
    Map policy = (Map) body.get("policy");
    Map filesystem = (Map) policy.get("filesystem");
    assertEquals(Arrays.asList(), filesystem.get("allowWrite"));
    assertTrue(body.containsKey("options"));
  }

  @Test
  void shouldRenderReadableJavaRunnerTemplate() {
    ObjectMapper objectMapper = new ObjectMapper();
    RestTemplate restTemplate = mock(RestTemplate.class);
    AgentServiceProperties properties = new AgentServiceProperties();
    properties.setSandboxUrl("http://sandbox.local:8000");
    Map<String, Object> sandboxResponse = new LinkedHashMap<String, Object>();
    sandboxResponse.put("returncode", Integer.valueOf(0));
    sandboxResponse.put("stdout", "{\"passed\":true,\"rows\":[]}\n");
    when(restTemplate.postForObject(any(String.class), any(Map.class), eq(Map.class)))
        .thenReturn(sandboxResponse);
    InterviewCodeRunner runner =
        new InterviewCodeRunner(
            objectMapper, restTemplate, properties, new ServiceResilience(properties));
    Map<String, Object> payload =
        validPayload("java", "class Solution { int sum(int n) { return n; } }");
    payload.put("functionName", "sum");

    runner.run(payload);

    ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
    verify(restTemplate)
        .postForObject(
            eq("http://sandbox.local:8000/v1/code-file"), bodyCaptor.capture(), eq(Map.class));
    String orchestrator = String.valueOf(bodyCaptor.getValue().get("code"));
    String javaRunner = decodeEmbeddedCode(orchestrator, "RUNNER_B64");
    assertTrue(javaRunner.contains("public class Runner {\n"));
    assertTrue(javaRunner.contains("method.getName().equals(\"sum\")"));
    assertFalse(javaRunner.contains("__FUNCTION_NAME__"));
  }

  @Test
  void shouldAddDefaultFieldsWhenSandboxResultOmitsThem() {
    ObjectMapper objectMapper = new ObjectMapper();
    RestTemplate restTemplate = mock(RestTemplate.class);
    AgentServiceProperties properties = new AgentServiceProperties();
    Map<String, Object> sandboxResponse = new LinkedHashMap<String, Object>();
    sandboxResponse.put("returncode", Integer.valueOf(0));
    sandboxResponse.put("stdout", "{\"message\":\"completed\"}\n");
    when(restTemplate.postForObject(any(String.class), any(Map.class), eq(Map.class)))
        .thenReturn(sandboxResponse);
    InterviewCodeRunner runner =
        new InterviewCodeRunner(
            objectMapper, restTemplate, properties, new ServiceResilience(properties));

    Map<String, Object> result =
        runner.run(validPayload("python", "def solution(value): return value"));

    assertEquals(Boolean.FALSE, result.get("passed"));
    assertEquals(Arrays.asList(), result.get("rows"));
    assertEquals("completed", result.get("message"));
  }

  @Test
  void shouldRejectOversizedSourceBeforeCallingSandbox() {
    ObjectMapper objectMapper = new ObjectMapper();
    RestTemplate restTemplate = mock(RestTemplate.class);
    AgentServiceProperties properties = new AgentServiceProperties();
    InterviewCodeRunner runner =
        new InterviewCodeRunner(
            objectMapper, restTemplate, properties, new ServiceResilience(properties));
    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("language", "java");
    payload.put("source", "x".repeat(128 * 1024 + 1));
    payload.put("functionName", "solution");
    Map<String, Object> test = new LinkedHashMap<String, Object>();
    test.put("args", Arrays.asList(Integer.valueOf(1)));
    test.put("expected", Integer.valueOf(1));
    payload.put("tests", Arrays.asList(test));

    Map<String, Object> result = runner.run(payload);

    assertEquals(Boolean.FALSE, result.get("passed"));
    assertTrue(String.valueOf(result.get("message")).contains("128KB"));
  }

  private Map<String, Object> validPayload(String language, String source) {
    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("language", language);
    payload.put("source", source);
    payload.put("functionName", "solution");
    Map<String, Object> test = new LinkedHashMap<String, Object>();
    test.put("name", "示例");
    test.put("args", Arrays.asList(Integer.valueOf(1)));
    test.put("expected", Integer.valueOf(1));
    payload.put("tests", Arrays.asList(test));
    return payload;
  }

  private String decodeEmbeddedChildCode(String orchestrator) {
    return decodeEmbeddedCode(orchestrator, "CODE_B64");
  }

  private String decodeEmbeddedCode(String orchestrator, String variableName) {
    Matcher matcher =
        Pattern.compile(Pattern.quote(variableName) + " = \\\"([^\\\"]+)\\\"")
            .matcher(orchestrator);
    assertTrue(matcher.find());
    return new String(Base64.getDecoder().decode(matcher.group(1)), StandardCharsets.UTF_8);
  }
}
