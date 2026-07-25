package com.jobbuddy.backend.modules.auth.dto.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 定义 Boss CLI 二维码结果。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BossCliQrResult {
  private Boolean ok;
  private JsonNode data;
  private JsonNode error;
}
