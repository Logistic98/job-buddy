package com.jobbuddy.backend.modules.chat.repository;

import com.jobbuddy.backend.modules.chat.entity.ChatAttachment;
import com.jobbuddy.backend.modules.chat.mapper.ChatAttachmentMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 封装聊天附件的属主查询、轮次绑定与删除操作。
 */
@Repository
public class ChatAttachmentRepository {
  private final ChatAttachmentMapper mapper;

  public ChatAttachmentRepository(ChatAttachmentMapper mapper) {
    this.mapper = mapper;
  }

  public void save(ChatAttachment attachment) {
    mapper.insert(attachment);
  }

  public ChatAttachment find(String tenantId, String userId, String attachmentId) {
    return mapper.findByOwner(tenantId, userId, attachmentId);
  }

  public boolean bind(
      String tenantId, String userId, String attachmentId, String sessionId, String turnId) {
    return mapper.bindToTurn(tenantId, userId, attachmentId, sessionId, turnId, Instant.now()) > 0;
  }

  public List<ChatAttachment> listBySession(String tenantId, String userId, String sessionId) {
    return mapper.listBySession(tenantId, userId, sessionId);
  }

  public boolean deleteUnbound(String tenantId, String userId, String attachmentId) {
    return mapper.deleteUnbound(tenantId, userId, attachmentId) > 0;
  }

  public void deleteBySession(String tenantId, String userId, String sessionId) {
    mapper.deleteBySession(tenantId, userId, sessionId);
  }
}
