package com.jobbuddy.backend.modules.prompt.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobbuddy.backend.common.util.JsonCodec;

public class UserProfileContext {
  private static final JsonCodec JSON = new JsonCodec();
  private final JsonNode profile;
  private final String summary;

  public UserProfileContext(Object profile, String summary) {
    this.profile = JSON.toTree(profile);
    this.summary = summary == null ? "" : summary;
  }

  public JsonNode getProfile() {
    return profile.deepCopy();
  }

  public String getSummary() {
    return summary;
  }

  public boolean isEmpty() {
    return profile.isEmpty() && summary.trim().isEmpty();
  }
}
