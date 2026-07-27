package com.jobbuddy.backend.modules.interview.dto.request;

import lombok.Data;

/**
 * 承载根据自然语言要求进行智能组卷的请求。
 */
@Data
public class InterviewSmartExamRequest {
  private String requirements;
}
