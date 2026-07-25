package com.jobbuddy.backend.modules.interview.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 承载面试编码运行响应数据。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InterviewCodeRunResponse {
  private Boolean passed;
  private JsonNode rows;
  private String message;
}
