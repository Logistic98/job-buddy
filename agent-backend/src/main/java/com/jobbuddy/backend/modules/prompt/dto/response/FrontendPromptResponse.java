package com.jobbuddy.backend.modules.prompt.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 承载前端提示词响应数据。
 */
@Data
public class FrontendPromptResponse {
  private String activeProfile;
  private JsonNode workbench;
  private JsonNode profile;
}
