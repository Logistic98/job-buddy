package com.jobbuddy.backend.modules.interview.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 承载面试考试提交请求参数。
 */
@Data
public class InterviewExamSubmitRequest {
  private JsonNode answers;
  private JsonNode codingResults;
}
