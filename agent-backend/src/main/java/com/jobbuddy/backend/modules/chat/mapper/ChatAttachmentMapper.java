package com.jobbuddy.backend.modules.chat.mapper;

import com.jobbuddy.backend.modules.chat.entity.ChatAttachment;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 映射聊天附件记录。
 */
public interface ChatAttachmentMapper {
  int insert(@Param("attachment") ChatAttachment attachment);

  ChatAttachment findByOwner(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("attachmentId") String attachmentId);

  int bindToTurn(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("attachmentId") String attachmentId,
      @Param("sessionId") String sessionId,
      @Param("turnId") String turnId,
      @Param("boundAt") Instant boundAt);

  List<ChatAttachment> listBySession(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("sessionId") String sessionId);

  int deleteUnbound(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("attachmentId") String attachmentId);

  int deleteBySession(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("sessionId") String sessionId);
}
