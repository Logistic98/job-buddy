package com.jobbuddy.backend.common.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthenticatedMenu {
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
}
