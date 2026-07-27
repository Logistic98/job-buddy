package com.jobbuddy.backend.modules.chat.service;

import java.util.List;
import java.util.Map;

/**
 * 返回附件绑定后的运行时上下文与用户消息幂等结果。
 */
public class ChatAttachmentTurnResult {
  private final boolean accepted;
  private final List<Map<String, Object>> attachments;

  public ChatAttachmentTurnResult(boolean accepted, List<Map<String, Object>> attachments) {
    this.accepted = accepted;
    this.attachments = attachments;
  }

  public boolean isAccepted() {
    return accepted;
  }

  public List<Map<String, Object>> getAttachments() {
    return attachments;
  }
}
