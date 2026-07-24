package com.jobbuddy.backend.modules.chat.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.Data;

@Data
public class ChatStreamRequest {
  @NotBlank(message = "消息不能为空")
  private String message;

  private String sessionId;

  @Size(max = 128, message = "turnId 长度不能超过 128")
  private String turnId;

  private String resumeId;
  private Boolean resumeAfterAuth;
  // 换一批：声明本次为确定性翻页（复用上一轮检索条件），后端据此短路任务理解直接翻到下一批候选。
  private Boolean flipJobs;
  private Map<String, Object> selectedJob;
  @JsonIgnore private String authenticatedTenantId;
  @JsonIgnore private String authenticatedUserId;
}
