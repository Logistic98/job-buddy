package com.jobbuddy.backend.modules.chat.vo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

  public IntentResult() {}

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

  public String getDomain() {
    return domain;
  }

  public String getIntent() {
    return intent;
  }

  public double getConfidence() {
    return confidence;
  }

  public List<String> getSecondary() {
    return secondary;
  }

  public String getRisk() {
    return risk;
  }

  public boolean isNeedsClarification() {
    return needsClarification;
  }

  public String getNextAction() {
    return nextAction;
  }

  public String getRouter() {
    return router;
  }

  public void setRouter(String router) {
    this.router = router;
  }

  public String getTraceId() {
    return traceId;
  }

  public void setTraceId(String traceId) {
    this.traceId = traceId;
  }

  public Map<String, Object> getSlots() {
    return slots;
  }

  public void setSlots(Map<String, Object> slots) {
    this.slots = slots;
  }
}
