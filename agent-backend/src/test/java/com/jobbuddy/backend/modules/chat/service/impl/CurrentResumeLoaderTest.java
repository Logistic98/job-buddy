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

class CurrentResumeLoaderTest {

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

  private ChatSessionState state(String tenantId, String userId, String resumeId) {
    ChatSessionState state = new ChatSessionState();
    state.tenantId = tenantId;
    state.userId = userId;
    state.sessionId = "session-a";
    state.resumeId = resumeId;
    return state;
  }
}
