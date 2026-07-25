package com.jobbuddy.backend.modules.project.dto.request;

import lombok.Data;

/**
 * 承载项目题目请求参数。
 */
@Data
public class ProjectQuestionRequest {
  private String question;
  private String answer;
  private String category;
  private String difficulty;
}
