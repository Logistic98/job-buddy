package com.jobbuddy.backend.modules.auth.dto.response;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 承载托管用户响应数据。
 */
@Data
public class ManagedUserResponse {
  private String userId;
  private String username;
  private String displayName;
  private boolean enabled;
  private String createdAt;
  private String updatedAt;
  private List<String> roleIds = new ArrayList<String>();
  private List<String> roleNames = new ArrayList<String>();
  private List<String> permissions = new ArrayList<String>();
}
