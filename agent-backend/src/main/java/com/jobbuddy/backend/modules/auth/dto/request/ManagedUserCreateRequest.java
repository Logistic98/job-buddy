package com.jobbuddy.backend.modules.auth.dto.request;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 承载托管用户创建请求参数。
 */
@Data
public class ManagedUserCreateRequest {
  private String username;
  private String password;
  private String displayName;
  private List<String> roleIds = new ArrayList<String>();
}
