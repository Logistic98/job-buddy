package com.jobbuddy.backend.modules.auth.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PermissionDefinitionResponse {
  private String permissionCode;
  private String permissionName;
}
