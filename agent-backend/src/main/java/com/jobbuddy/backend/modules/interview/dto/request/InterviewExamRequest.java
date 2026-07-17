package com.jobbuddy.backend.modules.interview.dto.request;

import java.util.List;
import lombok.Data;

@Data
public class InterviewExamRequest {
  private List<String> questionIds;
  private List<Rule> rules;
  private String bankType;
  private String category;
  private String difficulty;
  private String questionType;
  private Integer count;
  private String title;
  private Integer durationMinutes;
  private Boolean showAnswer;

  @Data
  public static class Rule {
    private Integer count;
    private String bankType;
    private String category;
    private String difficulty;
    private String questionType;
  }
}
