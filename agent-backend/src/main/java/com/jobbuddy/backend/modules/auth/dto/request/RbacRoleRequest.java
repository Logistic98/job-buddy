package com.jobbuddy.backend.modules.auth.dto.request;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class RbacRoleRequest {
  private String roleCode;
  private String roleName;
  private String description;
  private Boolean enabled;
  private List<String> menuIds = new ArrayList<String>();
}
