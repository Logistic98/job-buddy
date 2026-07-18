package com.jobbuddy.backend.modules.chat.service.impl;

import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.classifyMemoryType;

import com.jobbuddy.backend.modules.system.service.SystemSettingsService;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 记忆分层写入：短期记忆即当前会话上下文（chat_message_log，按会话隔离、随会话过期），普通问答只进短期记忆；
 * 长期记忆只承载跨会话稳定的偏好/约束/目标，必须是高信号信息才落库。这里只做轻量分层判断， 真正的去重、容量裁剪与启用开关由 SystemSettingsService 统一控制。
 */
class ChatMemoryWriter {
  private static final Logger log = LoggerFactory.getLogger(ChatMemoryWriter.class);
  private final SystemSettingsService settingsService;
  private final Executor executor;

  ChatMemoryWriter(SystemSettingsService settingsService, Executor executor) {
    this.settingsService = settingsService;
    this.executor = executor;
  }

  /** 长期记忆写入涉及文件读写与同步锁，放到后台执行，避免阻塞首包与答案流式链路。 */
  void captureLongTermMemoryAsync(
      final String tenantId, final String userId, final String message) {
    if (tenantId == null || tenantId.trim().isEmpty() || userId == null || userId.trim().isEmpty())
      return;
    if (message == null || message.trim().isEmpty()) return;
    executor.execute(
        new Runnable() {
          @Override
          public void run() {
            captureLongTermMemory(tenantId, userId, message);
          }
        });
  }

  private void captureLongTermMemory(String tenantId, String userId, String message) {
    if (message == null || message.trim().isEmpty()) return;
    String tier = classifyMemoryType(message);
    // 只有判定为长期记忆的稳定信息才写入持久化记忆，普通对话仅留在会话短期记忆中，不污染长期记忆。
    if (tier == null) return;
    try {
      settingsService.writeLocalMemory(tenantId, userId, tier, message.trim(), "chat");
    } catch (Exception e) {
      // 长期记忆写入失败不阻断问答主链路，但需留痕以便排查记忆丢失。
      log.warn("写入长期记忆失败 tier={}", tier, e);
    }
  }
}
