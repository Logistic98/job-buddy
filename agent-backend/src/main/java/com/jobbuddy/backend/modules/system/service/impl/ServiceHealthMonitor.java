package com.jobbuddy.backend.modules.system.service.impl;

import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.system.dto.response.ServiceStatusesResponse;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owns service endpoint projections and bounded health history. */
public class ServiceHealthMonitor {
  private static final int HEALTH_TIMEOUT_MILLIS = 1500;
  private static final int HEALTH_HISTORY_LIMIT = 60;

  private final AgentServiceProperties agentProperties;
  private final JobBuddyProperties jobProperties;
  private final JsonCodec jsonCodec;
  private Map<String, Object> monitoredStatuses = new LinkedHashMap<String, Object>();

  public ServiceHealthMonitor(
      AgentServiceProperties agentProperties, JobBuddyProperties jobProperties) {
    this.agentProperties = agentProperties;
    this.jobProperties = jobProperties;
    this.jsonCodec = new JsonCodec();
  }

  public Map<String, Object> serviceDefaults() {
    Map<String, Object> data = new LinkedHashMap<String, Object>();
    data.put("intentUrl", agentProperties.getIntentUrl());
    data.put("runtimeUrl", agentProperties.getRuntimeUrl());
    data.put("memoryUrl", agentProperties.getMemoryUrl());
    data.put("toolUrl", agentProperties.getToolUrl());
    data.put("evalUrl", agentProperties.getEvalUrl());
    data.put("connectTimeout", agentProperties.getConnectTimeout().toString());
    data.put("readTimeout", agentProperties.getReadTimeout().toString());
    return data;
  }

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

  public synchronized Map<String, Object> statuses() {
    if (monitoredStatuses.isEmpty()) refresh();
    return copyStatuses(monitoredStatuses);
  }

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

  private Map<String, Object> probeStatuses() {
    Map<String, Object> data = new LinkedHashMap<String, Object>();
    data.put("intent", status("intent", "Intent Service", agentProperties.getIntentUrl()));
    data.put("runtime", status("runtime", "Agent Runtime", agentProperties.getRuntimeUrl()));
    data.put("memory", status("memory", "Memory Service", agentProperties.getMemoryUrl()));
    data.put("tool", status("tool", "Tool Service", agentProperties.getToolUrl()));
    data.put("eval", status("eval", "Eval Service", agentProperties.getEvalUrl()));
    return data;
  }

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
    String healthUrl = healthUrl(baseUrl);
    try {
      HttpURLConnection connection = (HttpURLConnection) new URL(healthUrl).openConnection();
      connection.setRequestMethod("GET");
      connection.setConnectTimeout(HEALTH_TIMEOUT_MILLIS);
      connection.setReadTimeout(HEALTH_TIMEOUT_MILLIS);
      int code = connection.getResponseCode();
      boolean success = code >= 200 && code < 300;
      data.put("healthUrl", healthUrl);
      data.put("status", success ? "running" : "down");
      data.put("success", success);
      data.put("message", success ? "运行中" : "健康检查失败，HTTP " + code);
      connection.disconnect();
    } catch (Exception exception) {
      data.put("healthUrl", healthUrl);
      data.put("status", "down");
      data.put("success", false);
      data.put("message", exception.getMessage() == null ? "服务不可达" : exception.getMessage());
    }
    return data;
  }

  private String healthUrl(String baseUrl) {
    String value = baseUrl.trim();
    if (value.endsWith("/")) value = value.substring(0, value.length() - 1);
    return value + "/health";
  }
}
