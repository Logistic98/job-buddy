package com.jobbuddy.backend.modules.chat.service;

import com.jobbuddy.backend.modules.chat.dto.response.ChatAttachmentResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.web.multipart.MultipartFile;

/**
 * 管理聊天附件上传、轮次绑定和上下文输出。
 */
public interface ChatAttachmentService {
  ChatAttachmentResponse upload(MultipartFile file, String tenantId, String userId)
      throws IOException;

  List<Map<String, Object>> bindForTurn(
      List<String> attachmentIds, String tenantId, String userId, String sessionId, String turnId);

  ChatAttachmentTurnResult bindAndAppendUserMessage(
      List<String> attachmentIds,
      String tenantId,
      String userId,
      String sessionId,
      String turnId,
      String content);

  void deleteUnbound(String attachmentId, String tenantId, String userId);

  void deleteSession(String tenantId, String userId, String sessionId);
}
