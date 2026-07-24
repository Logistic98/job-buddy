package com.jobbuddy.backend.modules.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** 租户角色及其菜单授权信息。 */
@Data
@Schema(description = "租户角色")
public class RbacRoleResponse {
  @Schema(description = "角色标识", example = "role_recruiter")
  private String roleId;

  @Schema(description = "角色编码", example = "recruiter")
  private String roleCode;

  @Schema(description = "角色显示名称", example = "招聘负责人")
  private String roleName;

  @Schema(description = "角色职责说明")
  private String description;

  @Schema(description = "是否启用", example = "true")
  private boolean enabled;

  @Schema(description = "创建时间，ISO-8601 格式")
  private String createdAt;

  @Schema(description = "最后更新时间，ISO-8601 格式")
  private String updatedAt;

  @Schema(description = "角色已关联的菜单标识列表")
  private List<String> menuIds = new ArrayList<String>();
}
