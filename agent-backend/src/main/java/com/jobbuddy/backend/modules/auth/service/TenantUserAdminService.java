package com.jobbuddy.backend.modules.auth.service;

import com.jobbuddy.backend.modules.auth.dto.request.ManagedUserCreateRequest;
import com.jobbuddy.backend.modules.auth.dto.request.ManagedUserUpdateRequest;
import com.jobbuddy.backend.modules.auth.dto.response.ManagedUserResponse;
import java.util.List;

public interface TenantUserAdminService {
  List<ManagedUserResponse> listUsers(String tenantId);

  ManagedUserResponse create(String tenantId, ManagedUserCreateRequest request);

  ManagedUserResponse update(String tenantId, String userId, ManagedUserUpdateRequest request);

  ManagedUserResponse replaceRoles(String tenantId, String userId, List<String> roleIds);

  void resetPassword(String tenantId, String userId, String password);
}
