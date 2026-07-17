package com.jobbuddy.backend.modules.interview.dto.request;

import lombok.Data;

@Data
public class InterviewGenerateRequest {
  private String topic;
  private String category;
  private String difficulty;
  private String questionType;
  private String bankType;
  private String requirements;
  private String documentText;
  private Integer count;
}
