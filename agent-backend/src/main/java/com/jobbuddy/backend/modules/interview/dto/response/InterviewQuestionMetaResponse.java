package com.jobbuddy.backend.modules.interview.dto.response;

import java.util.List;
import lombok.Data;

/**
 * 承载面试题目元数据响应数据。
 */
@Data
public class InterviewQuestionMetaResponse {
  private List<String> bankTypes;
  private List<String> categories;
  private List<String> difficulties;
  private List<String> questionTypes;
  private List<Option> bankTypeOptions;

  /**
   * 定义选项。
   */
  @Data
  public static class Option {
    private String value;
    private String label;
  }
}
