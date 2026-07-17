package com.jobbuddy.backend.modules.interview.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InterviewCodeRunResponse {
  private Boolean passed;
  private JsonNode rows;
  private String message;
}
