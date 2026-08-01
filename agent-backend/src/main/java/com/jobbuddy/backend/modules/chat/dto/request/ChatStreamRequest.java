package com.jobbuddy.backend.modules.chat.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * 承载对话流式响应请求参数。
 */
@Data
public class ChatStreamRequest {
  @NotBlank(message = "消息不能为空")
  private String message;

  private String sessionId;

  @Size(max = 128, message = "turnId 长度不能超过 128")
  private String turnId;

  private String resumeId;

  @Size(max = 5, message = "每条消息最多上传 5 个附件")
  private List<String> attachmentIds;

  private Boolean resumeAfterAuth;

  @Size(max = 128, message = "resumeRunId 长度不能超过 128")
  private String resumeRunId;

  // 换一批：声明本次为确定性翻页（复用上一轮检索条件），后端据此短路任务理解直接翻到下一批候选。
  private Boolean flipJobs;

  @Valid private SelectedJobRequest selectedJob;
  @JsonIgnore private String authenticatedTenantId;
  @JsonIgnore private String authenticatedUserId;

  /**
   * 返回已校验且裁剪后的岗位分析上下文。
   *
   * @return 紧凑岗位上下文；未选择岗位时返回 null
   */
  @JsonIgnore
  public Map<String, Object> selectedJobMap() {
    return selectedJob == null ? null : selectedJob.toMap();
  }
}
