package com.jobbuddy.backend.modules.auth.dto.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BossCliQrResult {
  private Boolean ok;
  private JsonNode data;
  private JsonNode error;
}
