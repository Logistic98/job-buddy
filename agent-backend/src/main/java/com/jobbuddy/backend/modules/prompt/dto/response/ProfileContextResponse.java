package com.jobbuddy.backend.modules.prompt.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 承载画像上下文响应数据。
 */
@Data
public class ProfileContextResponse {
  private String summary;
  private JsonNode profile;
}
