package com.jobbuddy.backend.modules.chat.service.impl;

import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
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
        if (record.getParsed() == null || record.getParsed().isEmpty()) {
            record = resumeStorageService.parseSync(state.resumeId, state.sessionId);
        }
        return record;
    }
}
