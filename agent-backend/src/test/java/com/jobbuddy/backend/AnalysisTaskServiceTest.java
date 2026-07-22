package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.analysis.dto.AnalysisTaskResponse;
import com.jobbuddy.backend.modules.analysis.entity.AnalysisTask;
import com.jobbuddy.backend.modules.analysis.mapper.AnalysisTaskMapper;
import com.jobbuddy.backend.modules.analysis.service.impl.AnalysisTaskServiceImpl;
import com.jobbuddy.backend.modules.job.dto.command.JobFavoriteSaveCommand;
import com.jobbuddy.backend.modules.job.service.JobFavoriteService;
import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AnalysisTaskServiceTest {
  private AnalysisTaskServiceImpl service;

  @AfterEach
  void tearDown() {
    if (service != null) service.shutdown();
  }

  @Test
  void shouldReuseActiveTaskForSameOwnedResourceIncludingLongEncryptedJobKey() {
    AnalysisTaskMapper mapper = mock(AnalysisTaskMapper.class);
    String longJobKey = "encrypted-" + "x".repeat(600);
    AnalysisTask active =
        task("task-1", "tenant-a", "user-a", "favorite_job", longJobKey, "running");
    when(mapper.findActive("tenant-a", "user-a", "favorite_job", longJobKey)).thenReturn(active);
    service =
        new AnalysisTaskServiceImpl(
            mapper,
            new JsonCodec(),
            mock(ResumeStorageService.class),
            mock(JobFavoriteService.class));
    Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
    snapshot.put("favoriteKey", longJobKey);

    AnalysisTaskResponse response =
        service.startFavoriteJob(
            "tenant-a",
            "user-a",
            JobFavoriteSaveCommand.from(new JsonCodec().toTree(snapshot)),
            "resume-1");

    assertEquals("task-1", response.getTaskId());
    assertEquals("running", response.getStatus());
    verify(mapper, never()).insert(org.mockito.ArgumentMatchers.any(AnalysisTask.class));
  }

  @Test
  void shouldRejectCrossOwnerTaskLookup() {
    AnalysisTaskMapper mapper = mock(AnalysisTaskMapper.class);
    when(mapper.findOwned("task-1", "tenant-b", "user-b")).thenReturn(null);
    service =
        new AnalysisTaskServiceImpl(
            mapper,
            new JsonCodec(),
            mock(ResumeStorageService.class),
            mock(JobFavoriteService.class));

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> service.getOwned("task-1", "tenant-b", "user-b"));

    assertEquals("分析任务不存在", error.getMessage());
  }

  @Test
  void shouldRejectUnsupportedTaskTypeBeforeQueryingLatest() {
    AnalysisTaskMapper mapper = mock(AnalysisTaskMapper.class);
    service =
        new AnalysisTaskServiceImpl(
            mapper,
            new JsonCodec(),
            mock(ResumeStorageService.class),
            mock(JobFavoriteService.class));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.findLatest("tenant-a", "user-a", "unknown", "resource-1"));
    verify(mapper, never()).findLatest(anyString(), anyString(), anyString(), anyString());
  }

  private AnalysisTask task(
      String taskId,
      String tenantId,
      String userId,
      String type,
      String resourceKey,
      String status) {
    AnalysisTask task = new AnalysisTask();
    task.setTaskId(taskId);
    task.setTenantId(tenantId);
    task.setUserId(userId);
    task.setTaskType(type);
    task.setResourceKey(resourceKey);
    task.setStatus(status);
    task.setStage("analyzing");
    task.setMessage("正在分析");
    task.setRequestJson("{}");
    return task;
  }
}
