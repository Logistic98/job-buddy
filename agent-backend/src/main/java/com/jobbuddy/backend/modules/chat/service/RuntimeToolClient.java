package com.jobbuddy.backend.modules.chat.service;

import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeToolArguments;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeToolResult;

/**
 * 封装运行时工具下游调用。
 */
public interface RuntimeToolClient {
  /**
   * 调用运行时工具。
   *
   * @param toolName 工具名称
   * @param arguments 工具参数
   * @param sessionId 会话标识
   * @param workspaceDir 沙箱工作目录
   * @return 调用结果
   */
  RuntimeToolResult invoke(
      String toolName, RuntimeToolArguments arguments, String sessionId, String workspaceDir);
}
