package com.jobbuddy.backend.modules.project.dto.request;

import lombok.Data;

@Data
public class ProjectQuestionRequest {
  private String question;
  private String answer;
  private String category;
  private String difficulty;
}
