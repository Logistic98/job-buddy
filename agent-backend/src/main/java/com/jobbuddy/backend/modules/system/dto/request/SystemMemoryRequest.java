package com.jobbuddy.backend.modules.system.dto.request;

import lombok.Data;

/**
 * 承载系统记忆请求参数。
 */
@Data
public class SystemMemoryRequest {
  private String type;
  private String content;
  private String source;
  private Boolean enabled;
}
