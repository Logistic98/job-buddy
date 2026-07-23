package com.jobbuddy.backend.modules.auth.service;

import com.jobbuddy.backend.common.security.AuthenticatedUser;
import com.jobbuddy.backend.modules.auth.dto.response.LoginResponse;

public interface UserLoginService {
  LoginResponse login(String username, String password);

  AuthenticatedUser currentUser(String token);

  void logout(String token);

  void invalidateUserSessions(String userId);
}
