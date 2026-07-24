package com.jobbuddy.backend.modules.chat.client;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.resilience.ServiceResilience;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** agent-intent 分层意图识别服务的调用客户端。 */
@Component
public class IntentClient {
  private final RestTemplate restTemplate;
  private final AgentServiceProperties properties;
  private final ServiceResilience resilience;

  public IntentClient(
      RestTemplate restTemplate, AgentServiceProperties properties, ServiceResilience resilience) {
    this.restTemplate = restTemplate;
    this.properties = properties;
    this.resilience = resilience;
  }

  /** 调用 agent-intent 对用户消息做预分类。失败或统一响应无效时返回 {@code null}。 */
  public IntentResult classify(final String message) {
    final String baseUrl = intentBaseUrl();
    if (baseUrl.isEmpty()) return null;
    final String url = baseUrl + "/v1/intent/classify";
    return resilience.call(
        "agent-intent",
        () -> {
          IntentEnvelope response =
              restTemplate.postForObject(
                  url,
                  new IntentClassifyRequest(message == null ? "" : message),
                  IntentEnvelope.class);
          if (response == null || !response.isSuccessful() || response.getData() == null)
            return null;
          return response.getData().toIntentResult();
        },
        null,
        true);
  }

  private String intentBaseUrl() {
    String configured = properties.getIntentUrl();
    if (configured == null || configured.trim().isEmpty() || configured.contains("${")) return "";
    while (configured.endsWith("/")) configured = configured.substring(0, configured.length() - 1);
    return configured;
  }

  private record IntentClassifyRequest(String message) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private static final class IntentEnvelope {
    private Integer code;
    private IntentData data;

    public Integer getCode() {
      return code;
    }

    public void setCode(Integer code) {
      this.code = code;
    }

    public IntentData getData() {
      return data;
    }

    public void setData(IntentData data) {
      this.data = data;
    }

    private boolean isSuccessful() {
      return code != null
          && (code.intValue() == 0 || (code.intValue() >= 200 && code.intValue() < 300));
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private static final class IntentData {
    private String domain;
    private String intent;
    private Double confidence;
    private List<String> secondary;
    private String risk;
    private String router;

    @JsonProperty("needs_clarification")
    @JsonAlias("needsClarification")
    private Boolean needsClarification;

    @JsonProperty("next_action")
    @JsonAlias("nextAction")
    private String nextAction;

    private Map<String, Object> slots;

    @JsonProperty("trace_id")
    private String traceId;

    public void setDomain(String domain) {
      this.domain = domain;
    }

    public void setIntent(String intent) {
      this.intent = intent;
    }

    public void setConfidence(Double confidence) {
      this.confidence = confidence;
    }

    public void setSecondary(List<String> secondary) {
      this.secondary = secondary;
    }

    public void setRisk(String risk) {
      this.risk = risk;
    }

    public void setRouter(String router) {
      this.router = router;
    }

    public void setNeedsClarification(Boolean needsClarification) {
      this.needsClarification = needsClarification;
    }

    public void setNextAction(String nextAction) {
      this.nextAction = nextAction;
    }

    public void setSlots(Map<String, Object> slots) {
      this.slots = slots;
    }

    public void setTraceId(String traceId) {
      this.traceId = traceId;
    }

    private IntentResult toIntentResult() {
      IntentResult result =
          new IntentResult(
              text(domain, "unknown"),
              text(intent, "unknown"),
              confidence == null ? 0.0 : confidence.doubleValue(),
              secondary == null ? Collections.emptyList() : secondary,
              text(risk, "low"),
              Boolean.TRUE.equals(needsClarification),
              text(nextAction, "clarify"),
              slots == null ? new LinkedHashMap<>() : new LinkedHashMap<>(slots),
              text(traceId, null));
      result.setRouter(text(router, null));
      return result;
    }

    private static String text(String value, String fallback) {
      String normalized = value == null ? "" : value.trim();
      return normalized.isEmpty() ? fallback : normalized;
    }
  }
}
