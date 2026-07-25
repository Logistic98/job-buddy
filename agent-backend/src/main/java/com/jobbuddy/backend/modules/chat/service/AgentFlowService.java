package com.jobbuddy.backend.modules.chat.service;

import com.jobbuddy.backend.modules.chat.dto.request.ChatRequest;
import com.jobbuddy.backend.modules.chat.vo.ChatResponse;

/**
 * 定义 Agent 流程服务契约。
 */
public interface AgentFlowService {
  /**
   * 生成 Agent 流程答复。
   *
   * @param request 请求对象
   * @return  Agent 流程答复
   */
  ChatResponse answer(ChatRequest request);
}
