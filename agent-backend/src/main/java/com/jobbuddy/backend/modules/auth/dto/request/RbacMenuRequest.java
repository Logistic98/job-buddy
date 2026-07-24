package com.jobbuddy.backend.modules.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 创建或更新租户菜单时使用的请求。 */
@Data
@Schema(description = "菜单创建或更新请求")
public class RbacMenuRequest {
  @Schema(description = "父菜单标识；根节点不传", example = "menu_settings")
  private String parentId;

  @Schema(description = "租户内唯一的菜单编码，支持 2-64 位字母、数字、点、下划线和短横线", example = "settings.users")
  private String menuCode;

  @Schema(description = "菜单显示名称", example = "用户管理")
  private String menuName;

  @Schema(
      description = "菜单类型：directory、page 或 external",
      allowableValues = {"directory", "page", "external"},
      example = "page")
  private String menuType;

  @Schema(description = "内部路由；配置时必须以 / 开头", example = "/settings/users")
  private String routePath;

  @Schema(description = "前端组件注册键", example = "UserManagement")
  private String componentKey;

  @Schema(description = "外链地址；external 类型必须提供 http 或 https 地址")
  private String externalUrl;

  @Schema(description = "前端图标注册键", example = "users")
  private String iconKey;

  @Schema(description = "访问该菜单所需的后端权限码", example = "users:manage")
  private String permissionCode;

  @Schema(description = "同级菜单排序值；数值越小越靠前", example = "10")
  private Integer displayOrder;

  @Schema(description = "是否在导航中显示；省略默认为 true", example = "true")
  private Boolean visible;

  @Schema(description = "是否启用；省略默认为 true", example = "true")
  private Boolean enabled;
}
