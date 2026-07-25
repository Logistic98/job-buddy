package com.jobbuddy.backend.modules.auth.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 承载 Boss 登录状态响应数据。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BossLoginStatusResponse {
  private Boolean authRequired;
  private String provider;
  private String status;
  private Boolean ok;
  private Boolean authenticated;
  private Boolean search_authenticated;
  private Boolean cached;
  private String message;
  private String lastStatus;
  private String lastValidatedAt;
  private String qrSessionId;
  private String updatedAt;
  private String expiresAt;
  private String imageBase64;
  private String imageMime;
  private JsonNode qrVersion;
  private JsonNode riskMarker;
  private JsonNode finalUrl;
  private JsonNode error;
  private String homeDir;
}
