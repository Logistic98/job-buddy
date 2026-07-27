package com.jobbuddy.backend.modules.chat.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 返回可安全展示的聊天附件元数据。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatAttachmentResponse {
  private String attachmentId;
  private String fileName;
  private String contentType;
  private String suffix;
  private Long sizeBytes;
  private String parseStatus;
  private Integer characterCount;
  private Boolean truncated;
}
