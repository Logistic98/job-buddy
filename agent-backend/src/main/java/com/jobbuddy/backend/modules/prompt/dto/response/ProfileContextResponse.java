package com.jobbuddy.backend.modules.prompt.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class ProfileContextResponse {
  private String summary;
  private JsonNode profile;
}
