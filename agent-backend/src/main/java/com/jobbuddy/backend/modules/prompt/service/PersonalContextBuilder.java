package com.jobbuddy.backend.modules.prompt.service;

import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import com.jobbuddy.backend.modules.prompt.model.PersonalContext;

/**
 * 构建个人上下文。
 */
public interface PersonalContextBuilder {
  /**
   * 构建个人上下文。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param message 消息内容
   * @param intent 意图
   * @param state 状态
   * @return 构建结果
   */
  PersonalContext build(
      String tenantId, String userId, String message, IntentResult intent, ChatSessionState state);
}
