package com.jobbuddy.backend.modules.resume.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;

public class ResumeProfileRequest {
  private final JsonNode payload;

  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public ResumeProfileRequest(JsonNode payload) {
    this.payload = payload;
  }

  public JsonNode parsedPayload() {
    if (payload == null || payload.isNull()) return null;
    JsonNode parsed = payload.get("parsed");
    return parsed != null && parsed.isObject() ? parsed.deepCopy() : payload.deepCopy();
  }
}
