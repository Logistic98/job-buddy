package com.jobbuddy.backend.modules.auth.dto.request;

import java.util.List;
import lombok.Data;

/**
 * 承载托管用户更新请求参数。
 */
@Data
public class ManagedUserUpdateRequest {
  private String username;
  private String displayName;
  private Boolean enabled;
  private List<String> roleIds;
}
