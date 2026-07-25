package com.jobbuddy.backend.modules.interview.dto.request;

import lombok.Data;

/**
 * 承载面试生成请求参数。
 */
@Data
public class InterviewGenerateRequest {
  private String topic;
  private String category;
  private String difficulty;
  private String questionType;
  private String bankType;
  private String language;
  private String requirements;
  private String sourceUrl;
  private String documentText;
  private Integer count;
}
