package com.jobbuddy.backend.modules.prompt.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class FrontendPromptResponse {
  private String activeProfile;
  private JsonNode workbench;
  private JsonNode profile;
}
