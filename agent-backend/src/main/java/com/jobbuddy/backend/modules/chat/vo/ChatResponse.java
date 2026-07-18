package com.jobbuddy.backend.modules.chat.vo;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class ChatResponse {
  private String sessionId;
  private String answer;
  private IntentResult intent;
  private String executionMode;
  private List<String> plan;
  private List<TraceStep> trace;
  private List<String> suggestedQuestions;
  private List<Map<String, Object>> tools;
  private List<Map<String, Object>> memories;
  private Map<String, Object> evaluation;
  private Instant createdAt;

  public ChatResponse() {}

  public ChatResponse(
      String sessionId,
      String answer,
      IntentResult intent,
      String executionMode,
      List<String> plan,
      List<TraceStep> trace,
      List<String> suggestedQuestions,
      List<Map<String, Object>> tools,
      List<Map<String, Object>> memories,
      Map<String, Object> evaluation,
      Instant createdAt) {
    this.sessionId = sessionId;
    this.answer = answer;
    this.intent = intent;
    this.executionMode = executionMode;
    this.plan = plan;
    this.trace = trace;
    this.suggestedQuestions = suggestedQuestions;
    this.tools = tools;
    this.memories = memories;
    this.evaluation = evaluation;
    this.createdAt = createdAt;
  }

  public String getSessionId() {
    return sessionId;
  }

  public String getAnswer() {
    return answer;
  }

  public IntentResult getIntent() {
    return intent;
  }

  public String getExecutionMode() {
    return executionMode;
  }

  public List<String> getPlan() {
    return plan;
  }

  public List<TraceStep> getTrace() {
    return trace;
  }

  public List<String> getSuggestedQuestions() {
    return suggestedQuestions;
  }

  public List<Map<String, Object>> getTools() {
    return tools;
  }

  public List<Map<String, Object>> getMemories() {
    return memories;
  }

  public Map<String, Object> getEvaluation() {
    return evaluation;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
