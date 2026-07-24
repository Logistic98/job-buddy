package com.jobbuddy.backend.modules.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 租户菜单的层级、展示、路由和权限映射。 */
@Data
@Schema(description = "租户菜单")
public class RbacMenuResponse {
  @Schema(description = "菜单标识", example = "menu_settings_users")
  private String menuId;

  @Schema(description = "父菜单标识；根节点为空")
  private String parentId;

  @Schema(description = "菜单编码", example = "settings.users")
  private String menuCode;

  @Schema(description = "菜单显示名称", example = "用户管理")
  private String menuName;

  @Schema(
      description = "菜单类型",
      allowableValues = {"directory", "page", "external"},
      example = "page")
  private String menuType;

  @Schema(description = "内部路由")
  private String routePath;

  @Schema(description = "前端组件注册键")
  private String componentKey;

  @Schema(description = "外链地址")
  private String externalUrl;

  @Schema(description = "前端图标注册键")
  private String iconKey;

  @Schema(description = "访问该菜单所需的后端权限码")
  private String permissionCode;

  @Schema(description = "同级菜单排序值", example = "10")
  private int displayOrder;

  @Schema(description = "是否在导航中显示", example = "true")
  private boolean visible;

  @Schema(description = "是否启用", example = "true")
  private boolean enabled;

  @Schema(description = "创建时间，ISO-8601 格式")
  private String createdAt;

  @Schema(description = "最后更新时间，ISO-8601 格式")
  private String updatedAt;
}
