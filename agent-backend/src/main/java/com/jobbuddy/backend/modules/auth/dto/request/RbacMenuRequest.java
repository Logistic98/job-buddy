package com.jobbuddy.backend.modules.auth.dto.request;

import lombok.Data;

@Data
public class RbacMenuRequest {
  private String parentId;
  private String menuCode;
  private String menuName;
  private String menuType;
  private String routePath;
  private String componentKey;
  private String externalUrl;
  private String iconKey;
  private String permissionCode;
  private Integer displayOrder;
  private Boolean visible;
  private Boolean enabled;
}
