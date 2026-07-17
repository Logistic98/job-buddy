package com.jobbuddy.backend.modules.system.dto.request;

import lombok.Data;

@Data
public class SystemMemoryRequest {
  private String type;
  private String content;
  private String source;
  private Boolean enabled;
}
