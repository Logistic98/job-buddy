package com.jobbuddy.backend.modules.chat.service.impl;

import com.jobbuddy.backend.modules.chat.dto.response.ChatMessageResponse;
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.service.ChatSessionStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 为 Runtime 任务理解入口装配近期对话，确保省略式追问可以解析上一轮语义。
 */
final class ChatTaskContextBuilder {
  private static final Logger log = LoggerFactory.getLogger(ChatTaskContextBuilder.class);
  private static final int MAX_HISTORY_MESSAGES = 7;
  private static final int MAX_MESSAGE_CHARS = 1200;

  private final ChatSessionStore sessionStore;

  /**
   * 创建对话任务上下文构建器实例。
   *
   * @param sessionStore 会话存储
   */
  ChatTaskContextBuilder(ChatSessionStore sessionStore) {
    this.sessionStore = sessionStore;
  }

  /**
   * 返回“最近历史 + 当前用户消息”。当前消息可能已由异步持久化队列写入数据库，因此先移除末尾同内容用户消息， 再统一追加一次，避免 Runtime 把重复消息误判为两轮请求。
   *
   * @param state 状态
   * @param currentMessage 当前消息
   * @return 构建结果
   */
  List<Map<String, Object>> build(ChatSessionState state, String currentMessage) {
    List<Map<String, Object>> history = loadHistory(state);
    String current = compact(currentMessage);
    if (!history.isEmpty()) {
      Map<String, Object> latest = history.get(history.size() - 1);
      if ("user".equals(latest.get("role")) && current.equals(latest.get("content"))) {
        history.remove(history.size() - 1);
      }
    }

    int fromIndex = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
    List<Map<String, Object>> messages =
        new ArrayList<Map<String, Object>>(history.subList(fromIndex, history.size()));
    messages.add(message("user", current));
    return messages;
  }

  /**
   * 加载历史记录。
   *
   * @param state 状态
   * @return 历史消息列表
   */
  private List<Map<String, Object>> loadHistory(ChatSessionState state) {
    List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
    if (state == null
        || isBlank(state.tenantId)
        || isBlank(state.userId)
        || isBlank(state.sessionId)) {
      return messages;
    }
    try {
      List<ChatMessageResponse> stored =
          sessionStore.listMessages(state.tenantId, state.userId, state.sessionId);
      if (stored == null) return messages;
      for (ChatMessageResponse item : stored) {
        if (item == null) continue;
        String role = normalizeRole(item.getRole());
        String content = compact(item.getContent());
        if (role == null || content.isEmpty()) continue;
        messages.add(message(role, content));
      }
    } catch (RuntimeException error) {
      // 历史读取失败不能阻断当前轮；Runtime 仍可依赖 current message 与 previous_slots 执行受控降级。
      log.warn("读取任务理解近期对话失败 sessionId={}: {}", state.sessionId, conciseMessage(error));
    }
    return messages;
  }

  /**
   * 获取消息。
   *
   * @param role 角色
   * @param content 内容
   * @return 消息
   */
  private Map<String, Object> message(String role, String content) {
    Map<String, Object> value = new LinkedHashMap<String, Object>();
    value.put("role", role);
    value.put("content", content);
    return value;
  }

  /**
   * 规范化角色。
   *
   * @param value 输入值
   * @return 规范化后的角色
   */
  private String normalizeRole(String value) {
    if (value == null) return null;
    String role = value.trim().toLowerCase(Locale.ROOT);
    return "user".equals(role) || "assistant".equals(role) ? role : null;
  }

  /**
   * 压缩文本内容。
   *
   * @param value 输入值
   * @return 压缩结果
   */
  private String compact(String value) {
    if (value == null) return "";
    String text = value.trim();
    if (text.length() <= MAX_MESSAGE_CHARS) return text;
    return text.substring(0, MAX_MESSAGE_CHARS) + "...(truncated)";
  }

  /**
   * 判断值是否为空白。
   *
   * @param value 输入值
   * @return 值是否为空白是否成立
   */
  private boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  /**
   * 生成精简消息。
   *
   * @param error 异常
   * @return 精简消息
   */
  private String conciseMessage(Throwable error) {
    Throwable cause = error;
    while (cause != null && cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    String message = cause == null ? null : cause.getMessage();
    if (message == null || message.trim().isEmpty()) {
      message = cause == null ? "unknown" : cause.getClass().getSimpleName();
    }
    message = message.trim().replace('\n', ' ').replace('\r', ' ');
    return message.length() <= 180 ? message : message.substring(0, 180) + "...";
  }
}
