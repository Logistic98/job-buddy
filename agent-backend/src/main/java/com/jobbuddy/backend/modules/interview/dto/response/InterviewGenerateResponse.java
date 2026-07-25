package com.jobbuddy.backend.modules.interview.dto.response;

import java.util.List;
import lombok.Data;

/**
 * 承载面试生成响应数据。
 */
@Data
public class InterviewGenerateResponse {
  private Integer count;
  private List<InterviewQuestionResponse> items;
}
