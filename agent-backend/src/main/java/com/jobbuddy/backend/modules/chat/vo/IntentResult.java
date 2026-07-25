package com.jobbuddy.backend.modules.chat.vo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backend 接收的意图分类结果，保留路由、风险、澄清与槽位信息。
 */
public class IntentResult {
  private String domain;
  private String intent;
  private double confidence;
  private List<String> secondary = new ArrayList<String>();
  private String risk;
  private boolean needsClarification;
  private String nextAction;
  private String router;
  private String traceId;
  private Map<String, Object> slots = new LinkedHashMap<String, Object>();

  /**
   * 创建意图结果实例。
   */
  public IntentResult() {}

  /**
   * 创建意图结果实例。
   *
   * @param domain 业务域
   * @param intent 意图
   * @param confidence 置信度
   * @param secondary 次要结果
   * @param risk 风险等级
   * @param needsClarification 是否需要澄清
   * @param nextAction 下一项动作
   */
  public IntentResult(
      String domain,
      String intent,
      double confidence,
      List<String> secondary,
      String risk,
      boolean needsClarification,
      String nextAction) {
    this(
        domain,
        intent,
        confidence,
        secondary,
        risk,
        needsClarification,
        nextAction,
        new LinkedHashMap<String, Object>(),
        null);
  }

  /**
   * 创建意图结果实例。
   *
   * @param domain 业务域
   * @param intent 意图
   * @param confidence 置信度
   * @param secondary 次要结果
   * @param risk 风险等级
   * @param needsClarification 是否需要澄清
   * @param nextAction 下一项动作
   * @param slots 槽位
   */
  public IntentResult(
      String domain,
      String intent,
      double confidence,
      List<String> secondary,
      String risk,
      boolean needsClarification,
      String nextAction,
      Map<String, Object> slots) {
    this(domain, intent, confidence, secondary, risk, needsClarification, nextAction, slots, null);
  }

  /**
   * 创建意图结果实例。
   *
   * @param domain 业务域
   * @param intent 意图
   * @param confidence 置信度
   * @param secondary 次要结果
   * @param risk 风险等级
   * @param needsClarification 是否需要澄清
   * @param nextAction 下一项动作
   * @param slots 槽位
   * @param traceId Trace 标识
   */
  public IntentResult(
      String domain,
      String intent,
      double confidence,
      List<String> secondary,
      String risk,
      boolean needsClarification,
      String nextAction,
      Map<String, Object> slots,
      String traceId) {
    this.domain = domain;
    this.intent = intent;
    this.confidence = confidence;
    this.secondary = secondary;
    this.risk = risk;
    this.needsClarification = needsClarification;
    this.nextAction = nextAction;
    this.traceId = traceId;
    this.slots = slots == null ? new LinkedHashMap<String, Object>() : slots;
  }

  /**
   * 获取业务域。
   *
   * @return 业务域
   */
  public String getDomain() {
    return domain;
  }

  /**
   * 获取意图。
   *
   * @return 意图
   */
  public String getIntent() {
    return intent;
  }

  /**
   * 获取置信度。
   *
   * @return 置信度
   */
  public double getConfidence() {
    return confidence;
  }

  /**
   * 获取次级分类。
   *
   * @return 次要意图
   */
  public List<String> getSecondary() {
    return secondary;
  }

  /**
   * 获取风险。
   *
   * @return 风险
   */
  public String getRisk() {
    return risk;
  }

  /**
   * 判断是否需要澄清。
   *
   * @return 是否需要向用户澄清
   */
  public boolean isNeedsClarification() {
    return needsClarification;
  }

  /**
   * 获取下一步动作。
   *
   * @return 后续动作
   */
  public String getNextAction() {
    return nextAction;
  }

  /**
   * 获取路由来源。
   *
   * @return 路由结果
   */
  public String getRouter() {
    return router;
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
   * 获取 Trace 标识。
   *
   * @return Trace 步骤列表标识
   */
  public String getTraceId() {
    return traceId;
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
   * 获取槽位。
   *
   * @return 槽位
   */
  public Map<String, Object> getSlots() {
    return slots;
  }

  /**
   * 设置槽位。
   *
   * @param slots 槽位
   */
  public void setSlots(Map<String, Object> slots) {
    this.slots = slots;
  }
}
