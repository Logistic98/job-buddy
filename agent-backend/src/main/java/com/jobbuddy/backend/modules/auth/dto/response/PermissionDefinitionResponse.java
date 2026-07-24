package com.jobbuddy.backend.modules.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

/** 可绑定到动态菜单的权限定义。 */
@Data
@AllArgsConstructor
@Schema(description = "权限定义")
public class PermissionDefinitionResponse {
  @Schema(description = "权限码", example = "users:manage")
  private String permissionCode;

  @Schema(description = "权限显示名称", example = "用户管理")
  private String permissionName;
}
