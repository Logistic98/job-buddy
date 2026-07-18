package com.jobbuddy.backend.modules.analysis.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

public class AnalysisPartialResult {
  private final String section;
  private final String message;
  private final JsonNode payload;

  public AnalysisPartialResult(String section, String message, JsonNode payload) {
    this.section = section;
    this.message = message;
    this.payload = payload == null ? JsonNodeFactory.instance.objectNode() : payload.deepCopy();
  }

  public String getSection() {
    return section;
  }

  public String getMessage() {
    return message;
  }

  public JsonNode getPayload() {
    return payload.deepCopy();
  }
}
