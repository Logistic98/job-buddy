package com.jobbuddy.backend.modules.job.dto.response;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;

public class JobDetailResponse {
  private final JsonNode value;

  public JobDetailResponse(JsonNode value) {
    this.value = value;
  }

  @JsonValue
  public JsonNode value() {
    return value;
  }
}
