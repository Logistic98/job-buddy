package com.jobbuddy.backend.modules.interview.dto.response;

import java.util.List;
import lombok.Data;

@Data
public class InterviewQuestionMetaResponse {
  private List<String> bankTypes;
  private List<String> categories;
  private List<String> difficulties;
  private List<String> questionTypes;
  private List<Option> bankTypeOptions;

  @Data
  public static class Option {
    private String value;
    private String label;
  }
}
