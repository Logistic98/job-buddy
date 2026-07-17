package com.jobbuddy.backend.modules.auth.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BossLoginQrResponse {
  private Boolean authRequired;
  private String provider;
  private String message;
  private String qrSessionId;
  private JsonNode qrId;
  private String imageBase64;
  private String imageMime;
  private String expiresAt;
  private String updatedAt;
  private JsonNode qrVersion;
  private String status;
  private JsonNode error;
  private Boolean ok;
  private Boolean authenticated;
  private Boolean cached;
  private String lastStatus;
  private String lastValidatedAt;
}
