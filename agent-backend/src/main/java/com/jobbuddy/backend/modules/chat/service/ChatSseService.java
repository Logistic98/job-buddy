package com.jobbuddy.backend.modules.chat.service;

import com.jobbuddy.backend.modules.chat.dto.request.ChatStreamRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 定义对话 SSE 服务契约。
 */
public interface ChatSseService {
  /**
   * 建立聊天 SSE 流。
   *
   * @param request 请求对象
   * @return SSE 事件流
   */
  SseEmitter stream(ChatStreamRequest request);
}
