package com.jobbuddy.backend.modules.analysis.dto;

/**
 * 承载简历分析任务请求参数。
 */
public class ResumeAnalysisTaskRequest {
  private String resumeId;
  private String sessionId;

  /**
   * 获取简历标识。
   *
   * @return 简历标识
   */
  public String getResumeId() {
    return resumeId;
  }

  /**
   * 设置简历标识。
   *
   * @param resumeId 简历标识
   */
  public void setResumeId(String resumeId) {
    this.resumeId = resumeId;
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
   * 设置会话标识。
   *
   * @param sessionId 会话标识
   */
  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }
}
