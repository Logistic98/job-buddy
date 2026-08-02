package com.jobbuddy.backend.modules.auth.client;

import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.resilience.ServiceResilience;
import com.jobbuddy.backend.common.result.ErrorCode;
import com.jobbuddy.backend.modules.auth.BossAuthProviders;
import com.jobbuddy.backend.modules.auth.repository.AuthStateRepository;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Boss 工具能力客户端。
 *
 * <p>通过 agent-runtime 的 boss_browser 代理工具调用 agent-tool 中的 Boss 工具实现。
 *
 * <p>返回统一响应 {code, message, data}，供 BossCliServiceImpl 消费。
 */
@Service
public class BossBrowserClient {
  private static final String TOOL_NAME = "boss_browser";
  private static final String SERVICE_KEY = "boss-browser";

  private final RestTemplate restTemplate;
  private final AgentServiceProperties properties;
  private final ServiceResilience resilience;
  private final AuthStateRepository authStateRepository;

  /**
   * 创建 Boss 浏览器客户端实例。
   *
   * @param restTemplate HTTP 请求客户端
   * @param properties 配置属性
   * @param resilience 弹性策略
   * @param authStateRepository 认证状态存储访问
   */
  @Autowired
  public BossBrowserClient(
      RestTemplate restTemplate,
      AgentServiceProperties properties,
      ServiceResilience resilience,
      AuthStateRepository authStateRepository) {
    this.restTemplate = restTemplate;
    this.properties = properties;
    this.resilience = resilience;
    this.authStateRepository = authStateRepository;
  }

  /**
   * 创建 Boss 浏览器客户端实例。
   *
   * @param restTemplate HTTP 请求客户端
   * @param properties 配置属性
   * @param resilience 弹性策略
   */
  public BossBrowserClient(
      RestTemplate restTemplate, AgentServiceProperties properties, ServiceResilience resilience) {
    this(restTemplate, properties, resilience, null);
  }

  /**
   * 通过 Boss 浏览器客户端执行 GET 请求。
   *
   * @param path 路径
   * @return 查询结果
   */
  public Map<String, Object> get(String path) {
    return invoke(mapOperation(path), Collections.<String, Object>emptyMap());
  }

  /**
   * 向 Boss 工具服务发送 POST 请求。
   *
   * @param path 路径
   * @param body 请求体
   * @return 工具服务响应
   */
  public Map<String, Object> post(String path, Map<String, Object> body) {
    return invoke(mapOperation(path), body == null ? Collections.<String, Object>emptyMap() : body);
  }

  /**
   * 构造服务基础地址。
   *
   * @return 服务基础地址
   */
  public String baseUrl() {
    return properties.resolvedRuntimeUrl();
  }

  /**
   * 调用 Boss 浏览器客户端。
   *
   * @param operation 操作名称
   * @param payload 请求载荷
   * @return 调用
   */
  private Map<String, Object> invoke(String operation, Map<String, Object> payload) {
    final String url = baseUrl() + "/v1/runtime/tools/" + TOOL_NAME + "/invoke";
    Map<String, Object> arguments = new LinkedHashMap<String, Object>();
    arguments.put("operation", operation);
    Map<String, Object> effectivePayload = new LinkedHashMap<String, Object>();
    if (payload != null) effectivePayload.putAll(payload);
    String credentialJson = persistedCredentialJson();
    if (credentialJson != null && !credentialJson.trim().isEmpty()) {
      effectivePayload.put("credential_json", credentialJson);
    }
    arguments.put("payload", effectivePayload);

    final Map<String, Object> body = new LinkedHashMap<String, Object>();
    body.put("arguments", arguments);

    // Boss 工具访问外部站点，存在限速与验证码风险，调用视为非幂等不做重试；仅借助
    // ServiceResilience 的熔断能力，避免 Runtime 不可达时持续阻塞在读超时上。
    Map<String, Object> fallback =
        failure("BOSS_TOOL_UNREACHABLE", "无法连接 Runtime Boss 工具（" + url + "），请稍后重试");
    return resilience.call(
        SERVICE_KEY,
        new Supplier<Map<String, Object>>() {
          /**
           * 调用 Runtime 并提取 Boss 工具响应。
           *
           * @return 查询结果
           */
          @SuppressWarnings("unchecked")
          public Map<String, Object> get() {
            Map<String, Object> response = restTemplate.postForObject(url, body, Map.class);
            if (response == null) return failure("BOSS_TOOL_EMPTY", "Runtime Boss 工具返回空响应");
            Object data = response.get("data");
            if (!(data instanceof Map))
              return failure("BOSS_TOOL_BAD_RESPONSE", "Runtime Boss 工具响应缺少 data");
            Map<String, Object> toolResult = (Map<String, Object>) data;
            if (!Boolean.TRUE.equals(toolResult.get("success"))) {
              return failure("BOSS_TOOL_FAILED", String.valueOf(toolResult.get("error")));
            }
            Object output = toolResult.get("output");
            if (output instanceof Map)
              return persistRefreshedCredential(operation, (Map<String, Object>) output);
            return failure("BOSS_TOOL_BAD_OUTPUT", "Runtime Boss 工具输出不是对象");
          }
        },
        fallback,
        false);
  }

  /**
   * 将成功业务请求产生的新 Cookie 加密回写，并在进入业务 Service 前剥离敏感字段。
   *
   * <p>临时安全令牌由 Tool 的一次性 Chromium 静默刷新。如果只保留在 Tool 内存，服务重启后 Backend
   * 会再次注入数据库中的旧令牌，导致首次搜索重复进入不稳定的刷新路径。这里沿用二维码登录的内部凭据通道，把新值保存到当前租户和用户的 auth_state。
   *
   * @param operation Boss 操作
   * @param envelope 工具业务信封
   * @return 已剥离 credential_json 的业务信封
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> persistRefreshedCredential(
      String operation, Map<String, Object> envelope) {
    if (!mayRefreshCredential(operation) || envelope == null) return envelope;
    Object rawData = envelope.get("data");
    if (!(rawData instanceof Map)) return envelope;

    Map<String, Object> sanitizedData =
        new LinkedHashMap<String, Object>((Map<String, Object>) rawData);
    Object rawCredential = sanitizedData.remove("credential_json");
    if (rawCredential == null) return envelope;

    Map<String, Object> sanitizedEnvelope = new LinkedHashMap<String, Object>(envelope);
    sanitizedEnvelope.put("data", sanitizedData);
    if (!(rawCredential instanceof String)) return sanitizedEnvelope;
    String credentialJson = ((String) rawCredential).trim();
    if (credentialJson.isEmpty() || authStateRepository == null || !successfulEnvelope(envelope)) {
      return sanitizedEnvelope;
    }

    Map<String, Object> existing =
        authStateRepository.findByProvider(BossAuthProviders.STORAGE_PROVIDER);
    if (existing == null) {
      existing = authStateRepository.findByProvider(BossAuthProviders.LEGACY_STORAGE_PROVIDER);
    }
    Map<String, Object> metadata = new LinkedHashMap<String, Object>();
    if (existing != null && existing.get("metadata") instanceof Map) {
      metadata.putAll((Map<String, Object>) existing.get("metadata"));
    }
    authStateRepository.save(
        BossAuthProviders.STORAGE_PROVIDER, "logged_in", credentialJson, metadata);
    return sanitizedEnvelope;
  }

  /**
   * 判断操作是否可能在成功过程中静默刷新临时 Cookie。
   */
  private boolean mayRefreshCredential(String operation) {
    return "search".equals(operation)
        || "favorite_list".equals(operation)
        || "detail".equals(operation)
        || "profile".equals(operation);
  }

  /**
   * 判断工具业务信封是否成功。
   */
  private boolean successfulEnvelope(Map<String, Object> envelope) {
    Object rawCode = envelope.get("code");
    if (!(rawCode instanceof Number)) return false;
    int code = ((Number) rawCode).intValue();
    return code >= 200 && code < 300;
  }

  /**
   * 提取需持久化的凭据 JSON。
   *
   * @return 持久化凭据 JSON
   */
  private String persistedCredentialJson() {
    if (authStateRepository == null) return null;
    try {
      Map<String, Object> state =
          authStateRepository.findByProvider(BossAuthProviders.STORAGE_PROVIDER);
      String credentialJson = credentialJson(state);
      if (credentialJson != null) return credentialJson;
      return credentialJson(
          authStateRepository.findByProvider(BossAuthProviders.LEGACY_STORAGE_PROVIDER));
    } catch (Exception ignored) {
      return null;
    }
  }

  /**
   * 提取响应中的凭据 JSON。
   *
   * @param state 状态
   * @return 凭据 JSON
   */
  private String credentialJson(Map<String, Object> state) {
    Object value = state == null ? null : state.get("credentialJson");
    String credentialJson = value == null ? null : String.valueOf(value).trim();
    return credentialJson == null || credentialJson.isEmpty() ? null : credentialJson;
  }

  /**
   * 转换操作结果。
   *
   * @param path 路径
   * @return 操作类型
   */
  private String mapOperation(String path) {
    if ("/status".equals(path)) return "status";
    if ("/login/qr/start".equals(path)) return "qr_start";
    if ("/login/qr/status".equals(path)) return "qr_status";
    if ("/login/qr/cancel".equals(path)) return "qr_cancel";
    if ("/search".equals(path)) return "search";
    if ("/favorites".equals(path)) return "favorite_list";
    if ("/detail".equals(path)) return "detail";
    if ("/profile".equals(path)) return "profile";
    if ("/rate".equals(path)) return "rate";
    throw new IllegalArgumentException("不支持的 Boss 工具路径: " + path);
  }

  /**
   * 根据下游异常构造失败结果。
   *
   * @param code 编码
   * @param message 消息内容
   * @return 失败结果
   */
  private Map<String, Object> failure(String code, String message) {
    Map<String, Object> error = new LinkedHashMap<String, Object>();
    error.put("code", code);
    error.put("message", message);
    Map<String, Object> envelope = new LinkedHashMap<String, Object>();
    envelope.put("code", ErrorCode.DEPENDENCY_FAILURE.getCode());
    envelope.put("message", message);
    envelope.put("data", null);
    envelope.put("error", error);
    return envelope;
  }
}
