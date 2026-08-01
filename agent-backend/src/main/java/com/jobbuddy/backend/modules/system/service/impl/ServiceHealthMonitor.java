package com.jobbuddy.backend.modules.system.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.system.dto.response.ServiceStatusesResponse;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 管理服务端点投影与有界健康历史。
 */
public class ServiceHealthMonitor {
  private static final int HEALTH_TIMEOUT_MILLIS = 1500;
  private static final int MAX_SANDBOX_HEALTH_READ_TIMEOUT_MILLIS = 35_000;
  private static final int HEALTH_HISTORY_LIMIT = 60;

  private final AgentServiceProperties agentProperties;
  private final JobBuddyProperties jobProperties;
  private final JsonCodec jsonCodec;
  private Map<String, Object> monitoredStatuses = new LinkedHashMap<String, Object>();

  /**
   * 创建服务健康状态监视器实例。
   *
   * @param agentProperties Agent 配置属性
   * @param jobProperties 岗位配置属性
   */
  public ServiceHealthMonitor(
      AgentServiceProperties agentProperties, JobBuddyProperties jobProperties) {
    this.agentProperties = agentProperties;
    this.jobProperties = jobProperties;
    this.jsonCodec = new JsonCodec();
  }

  /**
   * 获取服务默认值。
   *
   * @return 服务默认值
   */
  public Map<String, Object> serviceDefaults() {
    Map<String, Object> data = new LinkedHashMap<String, Object>();
    data.put("intentUrl", agentProperties.getIntentUrl());
    data.put("runtimeUrl", agentProperties.getRuntimeUrl());
    data.put("memoryUrl", agentProperties.getMemoryUrl());
    data.put("toolUrl", agentProperties.getToolUrl());
    data.put("evalUrl", agentProperties.getEvalUrl());
    data.put("sandboxUrl", agentProperties.getSandboxUrl());
    data.put("connectTimeout", agentProperties.getConnectTimeout().toString());
    data.put("readTimeout", agentProperties.getReadTimeout().toString());
    return data;
  }

  /**
   * 获取运行时设置。
   *
   * @return 运行时设置
   */
  public Map<String, Object> runtimeSettings() {
    Map<String, Object> data = serviceDefaults();
    data.put("maxJobsPerRecommend", jobProperties.getMaxJobsPerRecommend());
    data.put("recommendOverfetchFactor", jobProperties.getRecommendOverfetchFactor());
    data.put("maxJobsPerScoring", jobProperties.getMaxJobsPerScoring());
    data.put("minimumRecommendedMatchScore", jobProperties.getMinimumRecommendedMatchScore());
    data.put("bossSearchMaxPages", jobProperties.getBossSearchMaxPages());
    data.put("bossSearchMaxPageDepth", jobProperties.getBossSearchMaxPageDepth());
    data.put("bossSearchCacheTtlMinutes", jobProperties.getBossSearchCacheTtlMinutes());
    data.put("bossSearchCooldownMinutesOnRisk", jobProperties.getBossSearchCooldownMinutesOnRisk());
    data.put("runtimeMaxTurns", jobProperties.getRuntimeMaxTurns());
    data.put("runtimeMaxToolCalls", jobProperties.getRuntimeMaxToolCalls());
    data.put("runtimeMaxFailures", jobProperties.getRuntimeMaxFailures());
    data.put("runtimeMaxTokens", jobProperties.getRuntimeMaxTokens());
    data.put("maxResumeBytes", jobProperties.getMaxResumeBytes());
    data.put("resumeWriterVersionLimit", jobProperties.getResumeWriterVersionLimit());
    return data;
  }

  /**
   * 获取状态。
   *
   * @return 状态
   */
  public synchronized Map<String, Object> statuses() {
    if (monitoredStatuses.isEmpty()) refresh();
    return copyStatuses(monitoredStatuses);
  }

  /**
   * 刷新服务健康状态。
   *
   * @return 刷新结果
   */
  public synchronized ServiceStatusesResponse refresh() {
    Map<String, Object> checkedStatuses = probeStatuses();
    for (Map.Entry<String, Object> entry : checkedStatuses.entrySet()) {
      if (!(entry.getValue() instanceof Map)) continue;
      @SuppressWarnings("unchecked")
      Map<String, Object> current = (Map<String, Object>) entry.getValue();
      List<Map<String, Object>> history = previousHistory(entry.getKey(), current.get("url"));
      Map<String, Object> point = new LinkedHashMap<String, Object>();
      point.put("status", current.get("status"));
      point.put("checkedAt", current.get("checkedAt"));
      point.put("message", current.get("message"));
      history.add(point);
      if (history.size() > HEALTH_HISTORY_LIMIT) {
        history =
            new ArrayList<Map<String, Object>>(
                history.subList(history.size() - HEALTH_HISTORY_LIMIT, history.size()));
      }
      current.put("history", history);
    }
    monitoredStatuses = checkedStatuses;
    return new ServiceStatusesResponse(jsonCodec.toTree(copyStatuses(monitoredStatuses)));
  }

  /**
   * 探测服务状态。
   *
   * @return 服务探测结果
   */
  private Map<String, Object> probeStatuses() {
    Map<String, Object> data = new LinkedHashMap<String, Object>();
    data.put("intent", status("intent", "Intent Service", agentProperties.getIntentUrl()));
    data.put("runtime", status("runtime", "Agent Runtime", agentProperties.getRuntimeUrl()));
    data.put("memory", status("memory", "Memory Service", agentProperties.getMemoryUrl()));
    data.put("tool", status("tool", "Tool Service", agentProperties.getToolUrl()));
    data.put("eval", status("eval", "Eval Service", agentProperties.getEvalUrl()));
    data.put("sandbox", status("sandbox", "Sandbox Service", agentProperties.getSandboxUrl()));
    return data;
  }

  /**
   * 获取上一轮健康历史。
   *
   * @param serviceId 服务标识
   * @param currentUrl 当前地址
   * @return 上一轮健康历史
   */
  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> previousHistory(String serviceId, Object currentUrl) {
    Object previousValue = monitoredStatuses.get(serviceId);
    if (!(previousValue instanceof Map)) return new ArrayList<Map<String, Object>>();
    Map<String, Object> previous = (Map<String, Object>) previousValue;
    if (!String.valueOf(previous.get("url")).equals(String.valueOf(currentUrl)))
      return new ArrayList<Map<String, Object>>();
    Object historyValue = previous.get("history");
    if (!(historyValue instanceof List)) return new ArrayList<Map<String, Object>>();
    List<Map<String, Object>> history = new ArrayList<Map<String, Object>>();
    for (Object point : (List<?>) historyValue) {
      if (point instanceof Map)
        history.add(new LinkedHashMap<String, Object>((Map<String, Object>) point));
    }
    return history;
  }

  /**
   * 复制状态。
   *
   * @param statuses 服务状态列表
   * @return 服务状态副本
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> copyStatuses(Map<String, Object> statuses) {
    Map<String, Object> copy = new LinkedHashMap<String, Object>();
    for (Map.Entry<String, Object> entry : statuses.entrySet()) {
      if (!(entry.getValue() instanceof Map)) continue;
      Map<String, Object> source =
          new LinkedHashMap<String, Object>((Map<String, Object>) entry.getValue());
      Object historyValue = source.get("history");
      if (historyValue instanceof List) {
        List<Map<String, Object>> history = new ArrayList<Map<String, Object>>();
        for (Object point : (List<?>) historyValue) {
          if (point instanceof Map)
            history.add(new LinkedHashMap<String, Object>((Map<String, Object>) point));
        }
        source.put("history", history);
      }
      copy.put(entry.getKey(), source);
    }
    return copy;
  }

  /**
   * 获取状态。
   *
   * @param id 标识
   * @param name 名称
   * @param baseUrl 服务基础地址
   * @return 当前状态
   */
  private Map<String, Object> status(String id, String name, String baseUrl) {
    Map<String, Object> data = new LinkedHashMap<String, Object>();
    data.put("id", id);
    data.put("name", name);
    data.put("url", baseUrl);
    data.put("checkedAt", java.time.Instant.now().toString());
    if (baseUrl == null || baseUrl.trim().isEmpty()) {
      data.put("status", "not_configured");
      data.put("success", false);
      data.put("message", "未配置服务地址");
      return data;
    }
    String healthUrl = healthUrl(id, baseUrl);
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) new URL(healthUrl).openConnection();
      connection.setRequestMethod("GET");
      connection.setConnectTimeout(HEALTH_TIMEOUT_MILLIS);
      connection.setReadTimeout(healthReadTimeoutMillis(id));
      int code = connection.getResponseCode();
      data.put("healthUrl", healthUrl);
      if (code < 200 || code >= 300) {
        data.put("status", "down");
        data.put("success", false);
        data.put("message", "健康检查失败，HTTP " + code);
        return data;
      }
      BusinessHealth businessHealth = readBusinessHealth(connection);
      String businessStatus = businessHealth.status().toUpperCase(Locale.ROOT);
      if ("sandbox".equals(id) && businessStatus.isBlank()) {
        data.put("status", "down");
        data.put("success", false);
        data.put("message", "健康检查失败，Sandbox readiness 未返回状态");
      } else if ("DEGRADED".equals(businessStatus)) {
        data.put("status", "degraded");
        data.put("success", false);
        data.put("message", withReason("运行降级", businessHealth.reason()));
      } else if (isBusinessDown(businessStatus)) {
        data.put("status", "down");
        data.put("success", false);
        data.put("message", withReason("健康检查失败，业务状态 " + businessStatus, businessHealth.reason()));
      } else if (businessStatus.isBlank() || isBusinessUp(businessStatus)) {
        data.put("status", "running");
        data.put("success", true);
        data.put("message", "运行中");
      } else {
        data.put("status", "unknown");
        data.put("success", false);
        data.put("message", withReason("未知业务状态 " + businessStatus, businessHealth.reason()));
      }
    } catch (Exception exception) {
      data.put("healthUrl", healthUrl);
      data.put("status", "down");
      data.put("success", false);
      data.put("message", exception.getMessage() == null ? "服务不可达" : exception.getMessage());
    } finally {
      if (connection != null) connection.disconnect();
    }
    return data;
  }

  /**
   * 从统一健康响应或直接健康对象中读取业务状态。
   *
   * <p>旧服务可能返回空正文、纯文本或不含 status 的 JSON，此时保留 HTTP 2xx 即运行中的兼容语义。
   *
   * @param connection 已返回 2xx 的健康检查连接
   * @return 业务健康信号
   */
  private BusinessHealth readBusinessHealth(HttpURLConnection connection) {
    try (InputStream input = connection.getInputStream()) {
      String body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      String json = body.trim();
      if (!json.startsWith("{")) return new BusinessHealth("", "");
      JsonNode root = jsonCodec.readTree(json);
      JsonNode details = root.path("data").isObject() ? root.path("data") : root;
      String status = text(details.path("status"));
      String reason =
          firstText(details.path("reason"), details.path("message"), root.path("reason"));
      return new BusinessHealth(status, reason);
    } catch (Exception ignored) {
      return new BusinessHealth("", "");
    }
  }

  /**
   * 判断业务健康状态是否明确不可用。
   *
   * @param status 标准化后的业务状态
   * @return 是否不可用
   */
  private boolean isBusinessDown(String status) {
    return "DOWN".equals(status) || "UNHEALTHY".equals(status) || "FAILED".equals(status);
  }

  /**
   * 判断业务健康状态是否明确正常。
   *
   * @param status 标准化后的业务状态
   * @return 是否正常
   */
  private boolean isBusinessUp(String status) {
    return "UP".equals(status)
        || "HEALTHY".equals(status)
        || "OK".equals(status)
        || "RUNNING".equals(status);
  }

  /**
   * 组合健康摘要和下游原因。
   *
   * @param summary 健康摘要
   * @param reason 下游原因
   * @return 用户可见消息
   */
  private String withReason(String summary, String reason) {
    return reason == null || reason.isBlank() ? summary : summary + "：" + reason;
  }

  /**
   * 返回第一个非空文本节点。
   *
   * @param candidates 候选节点
   * @return 文本
   */
  private String firstText(JsonNode... candidates) {
    for (JsonNode candidate : candidates) {
      String value = text(candidate);
      if (!value.isBlank()) return value;
    }
    return "";
  }

  /**
   * 安全读取文本节点。
   *
   * @param node JSON 节点
   * @return 文本
   */
  private String text(JsonNode node) {
    return node != null && node.isTextual() ? node.asText().trim() : "";
  }

  /**
   * 获取健康状态地址。
   *
   * @param serviceId 服务标识
   * @param baseUrl 服务基础地址
   * @return 健康状态地址
   */
  static String healthUrl(String serviceId, String baseUrl) {
    String value = baseUrl.trim();
    if (value.endsWith("/")) value = value.substring(0, value.length() - 1);
    return value + ("sandbox".equals(serviceId) ? "/ready" : "/health");
  }

  /**
   * Sandbox readiness 会真实启动 srt，使用与其执行预算对齐的读取超时；轻量健康端点保留短超时。
   *
   * @param serviceId 服务标识
   * @return 读取超时毫秒数
   */
  int healthReadTimeoutMillis(String serviceId) {
    if (!"sandbox".equals(serviceId)) return HEALTH_TIMEOUT_MILLIS;
    Duration configured = agentProperties.getSandboxHealthReadTimeout();
    if (configured == null || configured.isZero() || configured.isNegative())
      return HEALTH_TIMEOUT_MILLIS;
    long timeoutMillis;
    try {
      timeoutMillis = configured.toMillis();
    } catch (ArithmeticException exception) {
      return MAX_SANDBOX_HEALTH_READ_TIMEOUT_MILLIS;
    }
    return (int) Math.min(MAX_SANDBOX_HEALTH_READ_TIMEOUT_MILLIS, Math.max(1L, timeoutMillis));
  }

  private static final class BusinessHealth {
    private final String status;
    private final String reason;

    private BusinessHealth(String status, String reason) {
      this.status = status;
      this.reason = reason;
    }

    private String status() {
      return status;
    }

    private String reason() {
      return reason;
    }
  }
}
