package com.jobbuddy.backend.modules.auth.dto.response;

import lombok.Data;

@Data
public class RbacMenuResponse {
  private String menuId;
  private String parentId;
  private String menuCode;
  private String menuName;
  private String menuType;
  private String routePath;
  private String componentKey;
  private String externalUrl;
  private String iconKey;
  private String permissionCode;
  private int displayOrder;
  private boolean visible;
  private boolean enabled;
  private String createdAt;
  private String updatedAt;
}
