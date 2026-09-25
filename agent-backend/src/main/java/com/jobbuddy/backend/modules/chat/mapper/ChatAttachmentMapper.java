package com.jobbuddy.backend.modules.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jobbuddy.backend.modules.chat.entity.ChatAttachment;
import java.time.Instant;
import java.util.List;

/**
 * 聊天附件的类型化持久化操作；属主和绑定条件在数据库内原子校验。
 */
public interface ChatAttachmentMapper extends BaseMapper<ChatAttachment> {
  default ChatAttachment findByOwner(String tenantId, String userId, String attachmentId) {
    return selectOne(
        Wrappers.<ChatAttachment>lambdaQuery()
            .eq(ChatAttachment::getTenantId, tenantId)
            .eq(ChatAttachment::getUserId, userId)
            .eq(ChatAttachment::getAttachmentId, attachmentId));
  }

  default int bindToTurn(
      String tenantId,
      String userId,
      String attachmentId,
      String sessionId,
      String turnId,
      Instant boundAt) {
    return update(
        Wrappers.<ChatAttachment>lambdaUpdate()
            .eq(ChatAttachment::getTenantId, tenantId)
            .eq(ChatAttachment::getUserId, userId)
            .eq(ChatAttachment::getAttachmentId, attachmentId)
            .eq(ChatAttachment::getParseStatus, "ready")
            .and(
                binding ->
                    binding
                        .and(
                            unbound ->
                                unbound
                                    .isNull(ChatAttachment::getSessionId)
                                    .isNull(ChatAttachment::getTurnId))
                        .or(
                            bound ->
                                bound
                                    .eq(ChatAttachment::getSessionId, sessionId)
                                    .eq(ChatAttachment::getTurnId, turnId)))
            .set(ChatAttachment::getSessionId, sessionId)
            .set(ChatAttachment::getTurnId, turnId)
            .set(ChatAttachment::getBoundAt, boundAt));
  }

  default List<ChatAttachment> listBySession(String tenantId, String userId, String sessionId) {
    return selectList(
        Wrappers.<ChatAttachment>lambdaQuery()
            .eq(ChatAttachment::getTenantId, tenantId)
            .eq(ChatAttachment::getUserId, userId)
            .eq(ChatAttachment::getSessionId, sessionId)
            .orderByAsc(ChatAttachment::getCreatedAt, ChatAttachment::getAttachmentId));
  }

  default int deleteUnbound(String tenantId, String userId, String attachmentId) {
    return delete(
        Wrappers.<ChatAttachment>lambdaQuery()
            .eq(ChatAttachment::getTenantId, tenantId)
            .eq(ChatAttachment::getUserId, userId)
            .eq(ChatAttachment::getAttachmentId, attachmentId)
            .isNull(ChatAttachment::getSessionId)
            .isNull(ChatAttachment::getTurnId));
  }

  default int deleteBySession(String tenantId, String userId, String sessionId) {
    return delete(
        Wrappers.<ChatAttachment>lambdaQuery()
            .eq(ChatAttachment::getTenantId, tenantId)
            .eq(ChatAttachment::getUserId, userId)
            .eq(ChatAttachment::getSessionId, sessionId));
  }
}
