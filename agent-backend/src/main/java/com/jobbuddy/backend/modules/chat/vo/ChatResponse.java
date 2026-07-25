package com.jobbuddy.backend.modules.chat.vo;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 非流式聊天响应，汇总答案、意图、计划、Trace 与评估信息。
 */
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

  /**
   * 创建对话响应实例。
   */
  public ChatResponse() {}

  /**
   * 创建对话响应实例。
   *
   * @param sessionId 会话标识
   * @param answer 答案
   * @param intent 意图
   * @param executionMode 执行模式
   * @param plan 计划
   * @param trace Trace 步骤列表
   * @param suggestedQuestions 建议题目列表
   * @param tools 工具列表
   * @param memories 记忆列表
   * @param evaluation 评估
   * @param createdAt 创建时间
   */
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

  /**
   * 获取会话标识。
   *
   * @return 会话标识
   */
  public String getSessionId() {
    return sessionId;
  }

  /**
   * 获取答案。
   *
   * @return 回答内容
   */
  public String getAnswer() {
    return answer;
  }

  /**
   * 获取意图。
   *
   * @return 意图
   */
  public IntentResult getIntent() {
    return intent;
  }

  /**
   * 获取执行模式。
   *
   * @return 执行模式
   */
  public String getExecutionMode() {
    return executionMode;
  }

  /**
   * 获取执行计划。
   *
   * @return 执行计划
   */
  public List<String> getPlan() {
    return plan;
  }

  /**
   * 获取 Trace 步骤列表。
   *
   * @return Trace 步骤列表
   */
  public List<TraceStep> getTrace() {
    return trace;
  }

  /**
   * 获取推荐问题列表。
   *
   * @return 建议问题列表
   */
  public List<String> getSuggestedQuestions() {
    return suggestedQuestions;
  }

  /**
   * 获取工具列表。
   *
   * @return 工具列表
   */
  public List<Map<String, Object>> getTools() {
    return tools;
  }

  /**
   * 获取记忆列表。
   *
   * @return 记忆列表
   */
  public List<Map<String, Object>> getMemories() {
    return memories;
  }

  /**
   * 获取评估结果。
   *
   * @return 评估结果
   */
  public Map<String, Object> getEvaluation() {
    return evaluation;
  }

  /**
   * 获取创建时间。
   *
   * @return 创建时间
   */
  public Instant getCreatedAt() {
    return createdAt;
  }
}
