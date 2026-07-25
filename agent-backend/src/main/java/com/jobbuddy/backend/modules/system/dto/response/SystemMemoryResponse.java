package com.jobbuddy.backend.modules.system.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.Data;

/**
 * 承载系统记忆响应数据。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SystemMemoryResponse {
  private String id;
  private String type;
  private String content;
  private String source;
  private Boolean enabled;
  private String createdAt;
  private String updatedAt;
  private String scope;
  private Double score;
  private List<String> matchReasons;
}
