package com.jobbuddy.backend.modules.analysis.dto;

public class ResumeAnalysisTaskRequest {
  private String resumeId;
  private String sessionId;

  public String getResumeId() {
    return resumeId;
  }

  public void setResumeId(String resumeId) {
    this.resumeId = resumeId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }
}
