package com.jobbuddy.backend.modules.project.dto.request;

import lombok.Data;

@Data
public class ProjectQuestionGenerateRequest {
  private Integer count;
  private String focus;
}
