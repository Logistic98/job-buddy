package com.jobbuddy.backend.modules.job.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;

public class JobFavoriteRequest {
  private final JsonNode snapshot;

  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public JobFavoriteRequest(JsonNode snapshot) {
    this.snapshot = snapshot;
  }

  public JsonNode snapshot() {
    return snapshot == null ? null : snapshot.deepCopy();
  }

  public String jobKey() {
    return firstText("jobKey", "favoriteKey", "securityId", "id", "jobId", "encryptJobId");
  }

  public String resumeId() {
    return text("resumeId");
  }

  private String firstText(String... names) {
    for (String name : names) {
      String value = text(name);
      if (value != null) return value;
    }
    return null;
  }

  private String text(String name) {
    if (snapshot == null) return null;
    JsonNode value = snapshot.get(name);
    if (value == null || value.isNull()) return null;
    String text = value.asText().trim();
    return text.isEmpty() ? null : text;
  }
}
