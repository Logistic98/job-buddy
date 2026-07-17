package com.jobbuddy.backend.modules.auth.dto.response;

import com.jobbuddy.backend.common.security.AuthenticatedMenu;
import com.jobbuddy.backend.common.security.AuthenticatedUser;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class CurrentUserResponse {
  private String userId;
  private String username;
  private String displayName;
  private String role;
  private String tenantId;
  private String tenantCode;
  private List<String> roles = new ArrayList<String>();
  private List<String> permissions = new ArrayList<String>();
  private List<AuthenticatedMenu> menus = new ArrayList<AuthenticatedMenu>();

  public static CurrentUserResponse from(AuthenticatedUser user) {
    CurrentUserResponse response = new CurrentUserResponse();
    response.setUserId(user.getUserId());
    response.setUsername(user.getUsername());
    response.setDisplayName(user.getDisplayName());
    response.setRole(user.getRole());
    response.setTenantId(user.getTenantId());
    response.setTenantCode(user.getTenantCode());
    response.setRoles(new ArrayList<String>(user.getRoles()));
    response.setPermissions(new ArrayList<String>(user.getPermissions()));
    response.setMenus(new ArrayList<AuthenticatedMenu>(user.getMenus()));
    return response;
  }
}
