package com.jobbuddy.backend.modules.chat.service.impl;

import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.service.ResumeParsedContent;
import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;

/** 当前简历加载：按会话状态读取当前简历，未解析时先同步解析，供各分析链路共用。 */
class CurrentResumeLoader {
  private final ResumeStorageService resumeStorageService;

  CurrentResumeLoader(ResumeStorageService resumeStorageService) {
    this.resumeStorageService = resumeStorageService;
  }

  ResumeRecord loadCurrentResume(ChatSessionState state) {
    if (state == null || state.resumeId == null || state.resumeId.trim().isEmpty()) return null;
    ResumeRecord record = resumeStorageService.get(state.resumeId);
    if (record == null) return null;
    record = resumeStorageService.get(state.resumeId, state.tenantId, state.userId);
    // 文件夹整理会往 parsed 写入 folder 元数据，不能用"非空"判断已解析，必须校验真实内容字段。
    if (!ResumeParsedContent.hasContent(record.getParsed())) {
      record =
          resumeStorageService.parseSync(
              state.resumeId, state.sessionId, state.tenantId, state.userId);
    }
    return record;
  }
}
