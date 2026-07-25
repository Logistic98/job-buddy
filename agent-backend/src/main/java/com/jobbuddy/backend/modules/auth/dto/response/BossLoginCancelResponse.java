package com.jobbuddy.backend.modules.auth.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 承载 Boss 登录取消响应数据。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BossLoginCancelResponse {
  private Boolean ok;
  private String status;
  private JsonNode error;
}
