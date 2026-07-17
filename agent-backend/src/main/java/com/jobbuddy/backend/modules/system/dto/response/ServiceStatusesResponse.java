package com.jobbuddy.backend.modules.system.dto.response;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;

public class ServiceStatusesResponse {
  private final JsonNode statuses;

  public ServiceStatusesResponse(JsonNode statuses) {
    this.statuses = statuses;
  }

  @JsonValue
  public JsonNode statuses() {
    return statuses;
  }
}
