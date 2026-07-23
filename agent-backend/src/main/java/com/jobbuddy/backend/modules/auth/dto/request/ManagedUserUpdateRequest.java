package com.jobbuddy.backend.modules.auth.dto.request;

import java.util.List;
import lombok.Data;

@Data
public class ManagedUserUpdateRequest {
  private String username;
  private String displayName;
  private Boolean enabled;
  private List<String> roleIds;
}
