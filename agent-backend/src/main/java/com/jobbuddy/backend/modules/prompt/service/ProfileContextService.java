package com.jobbuddy.backend.modules.prompt.service;

import com.jobbuddy.backend.modules.prompt.model.UserProfileContext;

/**
 * 定义画像上下文服务契约。
 */
public interface ProfileContextService {
  /**
   * 获取当前画像上下文。
   *
   * @param userId 用户标识
   * @param resumeId 简历标识
   * @return 当前画像上下文
   */
  UserProfileContext current(String userId, String resumeId);
}
