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

  /**
   * 创建面试编码运行器实例。
   *
   * @param objectMapper JSON 对象映射器
   * @param restTemplate HTTP 请求客户端
   * @param properties 配置属性
   * @param resilience 弹性策略
   */
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

  /**
   * 在沙箱中执行代码并返回结构化结果。
   *
   * @param payload 请求载荷
   * @return 执行结果
   */
  public Map<String, Object> run(Map<String, Object> payload) {
    try {
      return runInSandbox(normalizeRequest(payload));
    } catch (Exception exception) {
      return failure(exception.getMessage() == null ? "代码运行失败" : exception.getMessage());
    }
  }

  /**
   * 规范化请求。
   *
   * @param payload 请求载荷
   * @return 规范化后的请求
   * @throws IOException 文件或网络读写失败时抛出
   */
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

  /**
   * 在沙箱中执行代码。
   *
   * @param request 请求对象
   * @return 执行结果：在沙箱
   * @throws Exception 执行失败时抛出
   */
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

  /**
   * 解析沙箱响应。
   *
   * @param response 响应对象
   * @return 沙箱响应
   * @throws Exception 执行失败时抛出
   */
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

  /**
   * 构建沙箱编排脚本。
   *
   * @param request 请求对象
   * @return 沙箱编排脚本
   * @throws Exception 执行失败时抛出
   */
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

  /**
   * 渲染函数执行模板。
   *
   * @param templateName 模板名称
   * @param functionName 目标函数名
   * @return 渲染后的函数模板
   * @throws IOException 文件或网络读写失败时抛出
   */
  private String renderFunctionTemplate(String templateName, String functionName)
      throws IOException {
    return loadTemplate(templateName).replace(FUNCTION_NAME_PLACEHOLDER, functionName);
  }

  /**
   * 加载模板。
   *
   * @param templateName 模板名称
   * @return 模板文本
   * @throws IOException 文件或网络读写失败时抛出
   */
  private String loadTemplate(String templateName) throws IOException {
    String path = TEMPLATE_ROOT + templateName;
    try (InputStream input = InterviewCodeRunner.class.getResourceAsStream(path)) {
      if (input == null) throw new IOException("代码运行模板不存在：" + path);
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /**
   * 构建沙箱策略。
   *
   * @return 沙箱策略
   */
  private Map<String, Object> sandboxPolicy() {
    Map<String, Object> network = new LinkedHashMap<String, Object>();
    network.put("allowedDomains", Collections.emptyList());
    network.put("deniedDomains", Collections.emptyList());

    Map<String, Object> filesystem = new LinkedHashMap<String, Object>();
    filesystem.put("denyRead", List.of("~/.ssh", "~/.aws", "~/.config/gcloud", "~/.kube"));
    filesystem.put("allowRead", Collections.emptyList());
    // agent-sandbox 会创建独立临时工作区。相对路径 "." 会在服务端解析为本次请求工作区，
    // 与服务端工作区白名单取交集后仍只允许写入该隔离目录。
    filesystem.put("allowWrite", List.of("."));
    filesystem.put("denyWrite", List.of(".env", "secrets/"));

    Map<String, Object> policy = new LinkedHashMap<String, Object>();
    policy.put("network", network);
    policy.put("filesystem", filesystem);
    return policy;
  }

  /**
   * 构建沙箱执行选项。
   *
   * @return 沙箱选项
   */
  private Map<String, Object> sandboxOptions() {
    Map<String, Object> options = new LinkedHashMap<String, Object>();
    options.put("timeout", Integer.valueOf(SANDBOX_TIMEOUT_SECONDS));
    options.put("check", Boolean.FALSE);
    return options;
  }

  /**
   * 规范化测试用例。
   *
   * @param testsValue 测试用例数据
   * @return 规范化后的测试用例
   */
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

  /**
   * 规范化函数名称。
   *
   * @param value 输入值
   * @return 规范化后的函数名称
   */
  private String normalizeFunctionName(String value) {
    String functionName = value == null || value.trim().isEmpty() ? "solution" : value.trim();
    if (!functionName.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
      throw new IllegalArgumentException("函数名不合法");
    }
    return functionName;
  }

  /**
   * 获取沙箱服务地址。
   *
   * @return 沙箱服务地址
   */
  private String sandboxBaseUrl() {
    return properties == null ? "" : properties.resolvedSandboxUrl();
  }

  /**
   * 获取 JSON 字符串。
   *
   * @param value 输入值
   * @return JSON 字符串
   * @throws Exception 执行失败时抛出
   */
  private String jsonString(String value) throws Exception {
    return objectMapper.writeValueAsString(value == null ? "" : value);
  }

  /**
   * 执行 Base64 编码。
   *
   * @param value 输入值
   * @return Base64 文本
   */
  private String base64(String value) {
    return Base64.getEncoder()
        .encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * 获取失败结果。
   *
   * @param message 消息内容
   * @return 失败结果
   */
  private Map<String, Object> failure(String message) {
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("passed", Boolean.FALSE);
    result.put("rows", Collections.emptyList());
    result.put("message", message == null || message.trim().isEmpty() ? "运行失败" : message);
    return result;
  }

  /**
   * 获取整数值。
   *
   * @param value 输入值
   * @param fallback 降级结果
   * @return 整数值
   */
  private int intValue(Object value, int fallback) {
    if (value instanceof Number) return ((Number) value).intValue();
    if (value == null) return fallback;
    try {
      return Integer.parseInt(String.valueOf(value));
    } catch (Exception ignored) {
      return fallback;
    }
  }

  /**
   * 获取首行文本。
   *
   * @param value 输入值
   * @param fallback 降级结果
   * @return 首行文本
   */
  private String firstLine(String value, String fallback) {
    if (value == null || value.trim().isEmpty()) return fallback;
    String text = value.trim();
    int newline = text.indexOf('\n');
    return newline >= 0 ? text.substring(0, newline) : text;
  }

  /**
   * 获取最后一行非空文本。
   *
   * @param value 输入值
   * @return 最后一行非空文本
   */
  private String lastNonEmptyLine(String value) {
    if (value == null) return null;
    String[] lines = value.split("\\r?\\n");
    for (int index = lines.length - 1; index >= 0; index--) {
      if (!lines[index].trim().isEmpty()) return lines[index].trim();
    }
    return null;
  }

  /**
   * 压缩文本内容。
   *
   * @param value 输入值
   * @param fallback 降级结果
   * @return 压缩结果
   */
  private String compact(String value, String fallback) {
    if (value == null || value.trim().isEmpty()) return fallback;
    String text = value.trim().replace('\n', ' ').replace('\r', ' ');
    return text.length() > 500 ? text.substring(0, 500) : text;
  }

  /**
   * 获取字符串值。
   *
   * @param value 输入值
   * @return 字符串值
   */
  private String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  /**
   * 承载执行请求参数。
   */
  private record ExecutionRequest(
      Language language, String source, String functionName, List<Map<String, Object>> tests) {}

  /**
   * 定义语言配置。
   */
  private enum Language {
    PYTHON("python", "python-harness.py.tpl"),
    JAVA("java", null),
    JAVASCRIPT("javascript", "javascript-harness.js.tpl");

    private final String id;
    private final String harnessTemplate;

    /**
     * 创建语言配置实例。
     *
     * @param id 标识
     * @param harnessTemplate 执行器模板
     */
    Language(String id, String harnessTemplate) {
      this.id = id;
      this.harnessTemplate = harnessTemplate;
    }

    /**
     * 获取标识。
     *
     * @return 标识
     */
    String id() {
      return id;
    }

    /**
     * 构建代码评测模板。
     *
     * @return 执行器模板
     */
    String harnessTemplate() {
      return harnessTemplate;
    }

    /**
     * 获取来源。
     *
     * @param value 输入值
     * @return 来源
     */
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
