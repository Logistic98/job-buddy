package com.jobbuddy.backend.modules.project.dto.request;

import lombok.Data;

/**
 * 承载项目题目生成请求参数。
 */
@Data
public class ProjectQuestionGenerateRequest {
  private Integer count;
  private String focus;
  private String requirements;
}
