package com.jobbuddy.backend.modules.interview.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class InterviewCodeRunRequest {
  private String language;
  private String source;
  private String functionName;
  private JsonNode tests;
}
