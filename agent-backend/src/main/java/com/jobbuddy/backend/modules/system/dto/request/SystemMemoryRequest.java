package com.jobbuddy.backend.modules.system.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 承载系统记忆请求参数。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SystemMemoryRequest {
  private String content;
  private String source;
  private Boolean enabled;
}
