package com.jobbuddy.backend.modules.project.dto.response;

import java.time.Instant;
import lombok.Data;

/**
 * 承载项目题目响应数据。
 */
@Data
public class ProjectQuestionResponse {
  private String questionId;
  private String projectId;
  private String question;
  private String answer;
  private String category;
  private String difficulty;
  private String source;
  private Instant createdAt;
}
