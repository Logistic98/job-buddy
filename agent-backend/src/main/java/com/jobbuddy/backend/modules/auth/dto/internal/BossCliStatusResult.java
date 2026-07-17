package com.jobbuddy.backend.modules.auth.dto.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BossCliStatusResult {
  private Boolean authenticated;

  @JsonProperty("search_authenticated")
  private Boolean searchAuthenticated;

  private Boolean ok;
  private String status;
  private String provider;
  private String homeDir;
  private JsonNode riskMarker;
  private JsonNode finalUrl;
  private JsonNode error;
}
