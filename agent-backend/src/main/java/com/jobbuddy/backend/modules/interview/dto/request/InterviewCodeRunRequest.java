package com.jobbuddy.backend.modules.interview.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 承载面试编码运行请求参数。
 */
@Data
public class InterviewCodeRunRequest {
  private String language;
  private String source;
  private String functionName;
  private JsonNode tests;
}
