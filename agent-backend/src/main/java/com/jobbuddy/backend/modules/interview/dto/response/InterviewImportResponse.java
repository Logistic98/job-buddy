package com.jobbuddy.backend.modules.interview.dto.response;

import java.util.List;
import lombok.Data;

/**
 * 承载面试导入响应数据。
 */
@Data
public class InterviewImportResponse {
  private Integer count;
  private List<InterviewQuestionResponse> items;
}
