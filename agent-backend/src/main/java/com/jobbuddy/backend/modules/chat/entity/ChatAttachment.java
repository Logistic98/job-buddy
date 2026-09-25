package com.jobbuddy.backend.modules.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Data;

/**
 * 描述聊天消息引用的用户文档及其解析状态。
 */
@Data
@TableName("chat_attachment")
public class ChatAttachment {
  @TableId(type = IdType.INPUT)
  private String attachmentId;

  private String tenantId;
  private String userId;
  private String sessionId;
  private String turnId;
  private String fileName;
  private String contentType;
  private String suffix;
  private String storagePath;
  private Long sizeBytes;
  private String sha256;
  private String parseStatus;
  private String parseError;
  private String extractedText;
  private Integer characterCount;
  private Boolean truncated;
  private Instant createdAt;
  private Instant boundAt;
}
