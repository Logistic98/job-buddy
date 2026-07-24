package com.jobbuddy.backend.modules.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** 创建或更新租户角色时使用的请求。 */
@Data
@Schema(description = "角色创建或更新请求")
public class RbacRoleRequest {
  @Schema(description = "租户内唯一的角色编码，支持 2-64 位字母、数字、点、下划线和短横线", example = "recruiter")
  private String roleCode;

  @Schema(description = "角色显示名称", example = "招聘负责人")
  private String roleName;

  @Schema(description = "角色职责说明", example = "负责岗位、简历和面试流程")
  private String description;

  @Schema(description = "是否启用；创建时省略默认为 true", example = "true")
  private Boolean enabled;

  @Schema(description = "角色关联的菜单标识列表")
  private List<String> menuIds = new ArrayList<String>();
}
