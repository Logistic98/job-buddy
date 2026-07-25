package com.jobbuddy.backend.modules.interview.dto.response;

import lombok.Data;

/**
 * 承载面试批次响应数据。
 */
@Data
public class InterviewBatchResponse {
  private Integer count;
  private String action;
}
