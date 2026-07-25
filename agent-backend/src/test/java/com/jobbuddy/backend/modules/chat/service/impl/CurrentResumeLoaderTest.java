package com.jobbuddy.backend.modules.chat.service.impl;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

/**
 * 验证 CurrentResumeLoader 的核心行为、异常路径与边界条件。
 */
class CurrentResumeLoaderTest {

  /**
   * 验证 CurrentResumeLoader 中简历的权限与租户隔离边界。
   */
  @Test
  void currentResumeMustBeLoadedThroughOwnerCheckedPath() {
    ResumeStorageService storage = mock(ResumeStorageService.class);
    ResumeRecord record = new ResumeRecord();
    LinkedHashMap<String, Object> parsed = new LinkedHashMap<String, Object>();
    parsed.put("skills", "Java");
    record.setParsed(parsed);
    when(storage.get("resume-a")).thenReturn(record);
    when(storage.get("resume-a", "tenant-a", "user-a")).thenReturn(record);
    ChatSessionState state = state("tenant-a", "user-a", "resume-a");

    ResumeRecord loaded = new CurrentResumeLoader(storage).loadCurrentResume(state);

    assertSame(record, loaded);
    verify(storage).get("resume-a", "tenant-a", "user-a");
  }

  /**
   * 验证 CurrentResumeLoader 中简历的权限与租户隔离边界。
   */
  @Test
  void ownerCheckFailureMustNotBeDowngradedToMissingResume() {
    ResumeStorageService storage = mock(ResumeStorageService.class);
    ResumeRecord record = new ResumeRecord();
    when(storage.get("resume-a")).thenReturn(record);
    when(storage.get("resume-a", "tenant-a", "user-b"))
        .thenThrow(new IllegalArgumentException("无权操作该简历"));
    ChatSessionState state = state("tenant-a", "user-b", "resume-a");

    assertThrows(
        IllegalArgumentException.class,
        () -> new CurrentResumeLoader(storage).loadCurrentResume(state));
  }

  /**
   * 验证状态。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param resumeId 简历标识
   * @return 测试会话状态
   */
  private ChatSessionState state(String tenantId, String userId, String resumeId) {
    ChatSessionState state = new ChatSessionState();
    state.tenantId = tenantId;
    state.userId = userId;
    state.sessionId = "session-a";
    state.resumeId = resumeId;
    return state;
  }
}
