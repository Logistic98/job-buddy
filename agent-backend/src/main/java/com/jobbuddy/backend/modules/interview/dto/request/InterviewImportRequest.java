package com.jobbuddy.backend.modules.interview.dto.request;

import java.util.List;
import lombok.Data;

/**
 * 承载面试导入请求参数。
 */
@Data
public class InterviewImportRequest {
  private List<InterviewQuestionRequest> items;
}
