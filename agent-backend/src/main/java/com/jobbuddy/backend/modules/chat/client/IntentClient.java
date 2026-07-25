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

/**
 * agent-intent 分层意图识别服务的调用客户端。
 */
@Component
public class IntentClient {
  private final RestTemplate restTemplate;
  private final AgentServiceProperties properties;
  private final ServiceResilience resilience;

  /**
   * 创建意图客户端实例。
   *
   * @param restTemplate HTTP 请求客户端
   * @param properties 配置属性
   * @param resilience 弹性策略
   */
  public IntentClient(
      RestTemplate restTemplate, AgentServiceProperties properties, ServiceResilience resilience) {
    this.restTemplate = restTemplate;
    this.properties = properties;
    this.resilience = resilience;
  }

  /**
   * 调用 agent-intent 对用户消息做预分类。失败或统一响应无效时返回 {@code null}。
   *
   * @param message 消息内容
   * @return 分类结果
   */
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

  /**
   * 构造意图服务基础地址。
   *
   * @return 意图服务基础地址
   */
  private String intentBaseUrl() {
    String configured = properties.getIntentUrl();
    if (configured == null || configured.trim().isEmpty() || configured.contains("${")) return "";
    while (configured.endsWith("/")) configured = configured.substring(0, configured.length() - 1);
    return configured;
  }

  /**
   * 承载意图分类请求参数。
   */
  private record IntentClassifyRequest(String message) {}

  /**
   * 定义意图响应封装。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private static final class IntentEnvelope {
    private Integer code;
    private IntentData data;

    /**
     * 获取编码。
     *
     * @return 编码
     */
    public Integer getCode() {
      return code;
    }

    /**
     * 设置编码。
     *
     * @param code 编码
     */
    public void setCode(Integer code) {
      this.code = code;
    }

    /**
     * 获取数据。
     *
     * @return 数据
     */
    public IntentData getData() {
      return data;
    }

    /**
     * 设置数据。
     *
     * @param data 数据
     */
    public void setData(IntentData data) {
      this.data = data;
    }

    /**
     * 判断是否成功。
     *
     * @return 响应是否成功
     */
    private boolean isSuccessful() {
      return code != null
          && (code.intValue() == 0 || (code.intValue() >= 200 && code.intValue() < 300));
    }
  }

  /**
   * 定义意图数据。
   */
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

    /**
     * 设置业务域。
     *
     * @param domain 业务域
     */
    public void setDomain(String domain) {
      this.domain = domain;
    }

    /**
     * 设置意图。
     *
     * @param intent 意图
     */
    public void setIntent(String intent) {
      this.intent = intent;
    }

    /**
     * 设置置信度。
     *
     * @param confidence 置信度
     */
    public void setConfidence(Double confidence) {
      this.confidence = confidence;
    }

    /**
     * 设置次级分类。
     *
     * @param secondary 次要结果
     */
    public void setSecondary(List<String> secondary) {
      this.secondary = secondary;
    }

    /**
     * 设置风险。
     *
     * @param risk 风险等级
     */
    public void setRisk(String risk) {
      this.risk = risk;
    }

    /**
     * 设置路由来源。
     *
     * @param router 路由结果
     */
    public void setRouter(String router) {
      this.router = router;
    }

    /**
     * 设置是否需要澄清。
     *
     * @param needsClarification 是否需要澄清
     */
    public void setNeedsClarification(Boolean needsClarification) {
      this.needsClarification = needsClarification;
    }

    /**
     * 设置下一步动作。
     *
     * @param nextAction 下一项动作
     */
    public void setNextAction(String nextAction) {
      this.nextAction = nextAction;
    }

    /**
     * 设置槽位。
     *
     * @param slots 槽位
     */
    public void setSlots(Map<String, Object> slots) {
      this.slots = slots;
    }

    /**
     * 设置 Trace 标识。
     *
     * @param traceId Trace 标识
     */
    public void setTraceId(String traceId) {
      this.traceId = traceId;
    }

    /**
     * 将意图服务响应转换为领域结果。
     *
     * @return 转换后的意图识别结果
     */
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

    /**
     * 读取文本内容。
     *
     * @param value 待处理值
     * @param fallback 降级
     * @return 文本内容
     */
    private static String text(String value, String fallback) {
      String normalized = value == null ? "" : value.trim();
      return normalized.isEmpty() ? fallback : normalized;
    }
  }
}
