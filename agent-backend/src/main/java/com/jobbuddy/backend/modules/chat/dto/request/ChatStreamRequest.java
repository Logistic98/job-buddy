package com.jobbuddy.backend.modules.chat.dto.request;

import lombok.Data;

import java.util.Map;

@Data
public class ChatStreamRequest {
    private String message;
    private String sessionId;
    private String resumeId;
    private Boolean resumeAfterAuth;
    // 换一批：声明本次为确定性翻页（复用上一轮检索条件），后端据此短路任务理解直接翻到下一批候选。
    private Boolean flipJobs;
    private Map<String, Object> selectedJob;
}
