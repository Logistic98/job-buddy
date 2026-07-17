package com.jobbuddy.backend.modules.auth.dto.response;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class RbacRoleResponse {
  private String roleId;
  private String roleCode;
  private String roleName;
  private String description;
  private boolean enabled;
  private String createdAt;
  private String updatedAt;
  private List<String> menuIds = new ArrayList<String>();
}
