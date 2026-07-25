package com.jobbuddy.backend.modules.auth.service;

import com.jobbuddy.backend.common.security.AuthenticatedUser;
import com.jobbuddy.backend.modules.auth.dto.response.LoginResponse;

/**
 * 认证本地用户，并统一管理会话签发、查询、撤销与批量失效。
 */
public interface UserLoginService {
  /**
   * 执行用户登录。
   *
   * @param username 用户名
   * @param password 密码
   * @param source 源数据
   * @return 登录结果
   */
  LoginResponse login(String username, String password, String source);

  /**
   * 获取当前用户。
   *
   * @param token 认证令牌
   * @return 当前用户
   */
  AuthenticatedUser currentUser(String token);

  /**
   * 执行用户退出登录。
   *
   * @param token 认证令牌
   */
  void logout(String token);

  /**
   * 使用户会话失效。
   *
   * @param userId 用户标识
   */
  void invalidateUserSessions(String userId);
}
