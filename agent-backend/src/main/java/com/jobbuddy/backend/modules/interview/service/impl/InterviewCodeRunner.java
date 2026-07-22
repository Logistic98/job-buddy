package com.jobbuddy.backend.modules.interview.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.resilience.ServiceResilience;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

/**
 * 编程题运行器。
 *
 * <p>不在 agent-backend 进程内执行用户代码。后端只负责校验输入、装配判题模板并提交给 agent-sandbox，由 agent-sandbox 基于
 * sandbox-runtime/srt 完成隔离执行。
 */
@Component
public class InterviewCodeRunner {
  private static final int MAX_TESTS = 20;
  private static final int MAX_SOURCE_BYTES = 128 * 1024;
  private static final int MAX_TEST_PAYLOAD_BYTES = 256 * 1024;
  private static final int CHILD_TIMEOUT_SECONDS = 5;
  private static final int SANDBOX_TIMEOUT_SECONDS = 12;
  private static final String SERVICE_KEY = "agent-sandbox";
  private static final String TEMPLATE_ROOT = "/code-runner/";
  private static final String ORCHESTRATOR_TEMPLATE = "sandbox-orchestrator.py.tpl";
  private static final String FUNCTION_NAME_PLACEHOLDER = "__FUNCTION_NAME__";

  private final ObjectMapper objectMapper;
  private final RestTemplate restTemplate;
  private final AgentServiceProperties properties;
  private final ServiceResilience resilience;

  public InterviewCodeRunner(
      ObjectMapper objectMapper,
      RestTemplate restTemplate,
      AgentServiceProperties properties,
      ServiceResilience resilience) {
    this.objectMapper = objectMapper;
    this.restTemplate = restTemplate;
    this.properties = properties;
    this.resilience = resilience;
  }

  public Map<String, Object> run(Map<String, Object> payload) {
    try {
      return runInSandbox(normalizeRequest(payload));
    } catch (Exception exception) {
      return failure(exception.getMessage() == null ? "代码运行失败" : exception.getMessage());
    }
  }

  private ExecutionRequest normalizeRequest(Map<String, Object> payload) throws IOException {
    Map<String, Object> safePayload =
        payload == null ? Collections.<String, Object>emptyMap() : payload;
    Language language = Language.from(stringValue(safePayload.get("language")));
    String source = stringValue(safePayload.get("source"));
    String functionName = normalizeFunctionName(stringValue(safePayload.get("functionName")));
    List<Map<String, Object>> tests = normalizeTests(safePayload.get("tests"));

    if (source == null || source.trim().isEmpty()) throw new IllegalArgumentException("代码不能为空");
    if (source.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
      throw new IllegalArgumentException("代码内容过大，最大允许 128KB");
    }
    if (tests.isEmpty()) throw new IllegalArgumentException("测试用例不能为空");
    if (objectMapper.writeValueAsBytes(tests).length > MAX_TEST_PAYLOAD_BYTES) {
      throw new IllegalArgumentException("测试用例内容过大，最大允许 256KB");
    }
    return new ExecutionRequest(language, source, functionName, tests);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> runInSandbox(ExecutionRequest request) throws Exception {
    Map<String, Object> body = new LinkedHashMap<String, Object>();
    body.put("code", buildSandboxOrchestrator(request));
    body.put("suffix", ".py");
    body.put("interpreter", "python3");
    body.put("policy", sandboxPolicy());
    body.put("options", sandboxOptions());

    // 判题属于非幂等代码执行，不做重试。熔断器用于避免沙箱不可达时持续阻塞，HTTP 错误和服务不可达
    // 分别保留诊断文案，便于调用方定位编排失败或服务未启动。
    if (resilience.isOpen(SERVICE_KEY)) {
      return failure("agent-sandbox 暂时不可用（熔断中），请稍后重试");
    }
    Map<String, Object> response;
    try {
      response = restTemplate.postForObject(sandboxBaseUrl() + "/v1/code-file", body, Map.class);
      resilience.recordSuccess(SERVICE_KEY);
    } catch (RestClientResponseException exception) {
      resilience.recordFailure(SERVICE_KEY);
      return failure(
          "agent-sandbox 调用失败："
              + compact(
                  exception.getResponseBodyAsString(), "HTTP " + exception.getRawStatusCode()));
    } catch (RestClientException exception) {
      resilience.recordFailure(SERVICE_KEY);
      return failure(
          "agent-sandbox 不可用，请确认服务已启动并配置 agent.services.sandbox-url：" + exception.getMessage());
    }
    return parseSandboxResponse(response);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parseSandboxResponse(Map<String, Object> response) throws Exception {
    if (response == null) return failure("agent-sandbox 返回空响应");
    int returnCode = intValue(response.get("returncode"), -1);
    String stdout = stringValue(response.get("stdout"));
    String stderr = stringValue(response.get("stderr"));
    if (returnCode != 0) {
      return failure(
          firstLine(
              (stderr == null ? "" : stderr) + "\n" + (stdout == null ? "" : stdout), "沙箱执行失败"));
    }
    String json = lastNonEmptyLine(stdout);
    if (json == null || json.trim().isEmpty()) return failure("沙箱运行无输出");
    Map<String, Object> result = objectMapper.readValue(json, Map.class);
    result.putIfAbsent("passed", Boolean.FALSE);
    result.putIfAbsent("rows", Collections.emptyList());
    return result;
  }

  private String buildSandboxOrchestrator(ExecutionRequest request) throws Exception {
    String childCode = request.source();
    String runnerCode = "";
    if (request.language().harnessTemplate() != null) {
      childCode =
          request.source()
              + "\n\n"
              + renderFunctionTemplate(
                  request.language().harnessTemplate(), request.functionName());
    } else {
      runnerCode = renderFunctionTemplate("java-runner.java.tpl", request.functionName());
    }

    return loadTemplate(ORCHESTRATOR_TEMPLATE)
        .replace("__LANGUAGE__", jsonString(request.language().id()))
        .replace("__CODE_B64__", jsonString(base64(childCode)))
        .replace("__RUNNER_B64__", jsonString(base64(runnerCode)))
        .replace(
            "__TESTS_B64__", jsonString(base64(objectMapper.writeValueAsString(request.tests()))))
        .replace("__TIMEOUT_SECONDS__", String.valueOf(CHILD_TIMEOUT_SECONDS));
  }

  private String renderFunctionTemplate(String templateName, String functionName)
      throws IOException {
    return loadTemplate(templateName).replace(FUNCTION_NAME_PLACEHOLDER, functionName);
  }

  private String loadTemplate(String templateName) throws IOException {
    String path = TEMPLATE_ROOT + templateName;
    try (InputStream input = InterviewCodeRunner.class.getResourceAsStream(path)) {
      if (input == null) throw new IOException("代码运行模板不存在：" + path);
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private Map<String, Object> sandboxPolicy() {
    Map<String, Object> network = new LinkedHashMap<String, Object>();
    network.put("allowedDomains", Collections.emptyList());
    network.put("deniedDomains", Collections.emptyList());

    Map<String, Object> filesystem = new LinkedHashMap<String, Object>();
    filesystem.put("denyRead", List.of("~/.ssh", "~/.aws", "~/.config/gcloud", "~/.kube"));
    filesystem.put("allowRead", Collections.emptyList());
    // agent-sandbox 会创建独立临时工作区。allowWrite 为空表示沿用工作区白名单；不能请求宿主机
    // /tmp，否则与请求工作区取交集后会得到空白名单，导致判题无法创建子进程文件。
    filesystem.put("allowWrite", Collections.emptyList());
    filesystem.put("denyWrite", List.of(".env", "secrets/"));

    Map<String, Object> policy = new LinkedHashMap<String, Object>();
    policy.put("network", network);
    policy.put("filesystem", filesystem);
    return policy;
  }

  private Map<String, Object> sandboxOptions() {
    Map<String, Object> options = new LinkedHashMap<String, Object>();
    options.put("timeout", Integer.valueOf(SANDBOX_TIMEOUT_SECONDS));
    options.put("check", Boolean.FALSE);
    return options;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> normalizeTests(Object testsValue) {
    List<Map<String, Object>> tests = new ArrayList<Map<String, Object>>();
    if (!(testsValue instanceof List)) return tests;
    for (Object item : (List<Object>) testsValue) {
      if (!(item instanceof Map)) continue;
      tests.add(new LinkedHashMap<String, Object>((Map<String, Object>) item));
      if (tests.size() >= MAX_TESTS) break;
    }
    return tests;
  }

  private String normalizeFunctionName(String value) {
    String functionName = value == null || value.trim().isEmpty() ? "solution" : value.trim();
    if (!functionName.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
      throw new IllegalArgumentException("函数名不合法");
    }
    return functionName;
  }

  private String sandboxBaseUrl() {
    return properties == null ? "" : properties.resolvedSandboxUrl();
  }

  private String jsonString(String value) throws Exception {
    return objectMapper.writeValueAsString(value == null ? "" : value);
  }

  private String base64(String value) {
    return Base64.getEncoder()
        .encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
  }

  private Map<String, Object> failure(String message) {
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("passed", Boolean.FALSE);
    result.put("rows", Collections.emptyList());
    result.put("message", message == null || message.trim().isEmpty() ? "运行失败" : message);
    return result;
  }

  private int intValue(Object value, int fallback) {
    if (value instanceof Number) return ((Number) value).intValue();
    if (value == null) return fallback;
    try {
      return Integer.parseInt(String.valueOf(value));
    } catch (Exception ignored) {
      return fallback;
    }
  }

  private String firstLine(String value, String fallback) {
    if (value == null || value.trim().isEmpty()) return fallback;
    String text = value.trim();
    int newline = text.indexOf('\n');
    return newline >= 0 ? text.substring(0, newline) : text;
  }

  private String lastNonEmptyLine(String value) {
    if (value == null) return null;
    String[] lines = value.split("\\r?\\n");
    for (int index = lines.length - 1; index >= 0; index--) {
      if (!lines[index].trim().isEmpty()) return lines[index].trim();
    }
    return null;
  }

  private String compact(String value, String fallback) {
    if (value == null || value.trim().isEmpty()) return fallback;
    String text = value.trim().replace('\n', ' ').replace('\r', ' ');
    return text.length() > 500 ? text.substring(0, 500) : text;
  }

  private String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private record ExecutionRequest(
      Language language, String source, String functionName, List<Map<String, Object>> tests) {}

  private enum Language {
    PYTHON("python", "python-harness.py.tpl"),
    JAVA("java", null),
    JAVASCRIPT("javascript", "javascript-harness.js.tpl");

    private final String id;
    private final String harnessTemplate;

    Language(String id, String harnessTemplate) {
      this.id = id;
      this.harnessTemplate = harnessTemplate;
    }

    String id() {
      return id;
    }

    String harnessTemplate() {
      return harnessTemplate;
    }

    static Language from(String value) {
      String language = value == null ? "" : value.trim().toLowerCase();
      if ("py".equals(language) || "python".equals(language) || "python3".equals(language)) {
        return PYTHON;
      }
      if ("java".equals(language)) return JAVA;
      if ("js".equals(language) || "node".equals(language) || "javascript".equals(language)) {
        return JAVASCRIPT;
      }
      throw new IllegalArgumentException("当前仅支持 Python、Java、JavaScript 运行样例");
    }
  }
}
