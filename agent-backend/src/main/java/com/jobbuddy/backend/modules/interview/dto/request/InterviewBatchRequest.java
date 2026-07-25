package com.jobbuddy.backend.modules.interview.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import lombok.Data;

/**
 * 承载面试批次请求参数。
 */
@Data
public class InterviewBatchRequest {
  private List<String> questionIds;
  private String action;
  private String category;
  private String difficulty;
  private JsonNode tags;
}
