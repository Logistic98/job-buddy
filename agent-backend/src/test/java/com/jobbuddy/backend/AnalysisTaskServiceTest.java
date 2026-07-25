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

/**
 * 验证 AnalysisTaskService 的核心行为、异常路径与边界条件。
 */
class AnalysisTaskServiceTest {
  private AnalysisTaskServiceImpl service;

  /**
   * 清理测试创建的资源与上下文。
   */
  @AfterEach
  void tearDown() {
    if (service != null) service.shutdown();
  }

  /**
   * 验证 AnalysisTaskService 中岗位的去重与幂等边界。
   */
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

  /**
   * 验证 AnalysisTaskService 的权限与租户隔离边界。
   */
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

  /**
   * 验证 AnalysisTaskService 的流式生命周期与中断边界。
   */
  @Test
  void shouldCancelOnlyAnOwnedActiveTask() {
    AnalysisTaskMapper mapper = mock(AnalysisTaskMapper.class);
    AnalysisTask running = task("task-1", "tenant-a", "user-a", "favorite_job", "job-1", "running");
    AnalysisTask cancelled =
        task("task-1", "tenant-a", "user-a", "favorite_job", "job-1", "cancelled");
    cancelled.setStage("cancelled");
    when(mapper.findOwned("task-1", "tenant-a", "user-a"))
        .thenReturn(running)
        .thenReturn(cancelled);
    when(mapper.markCancelled("task-1")).thenReturn(1);
    service =
        new AnalysisTaskServiceImpl(
            mapper,
            new JsonCodec(),
            mock(ResumeStorageService.class),
            mock(JobFavoriteService.class));

    AnalysisTaskResponse response = service.cancel("task-1", "tenant-a", "user-a");

    assertEquals("cancelled", response.getStatus());
    assertEquals("cancelled", response.getStage());
    verify(mapper).markCancelled("task-1");
  }

  /**
   * 验证 AnalysisTaskService 的权限与租户隔离边界。
   */
  @Test
  void shouldRejectCrossOwnerCancellationWithoutChangingTaskState() {
    AnalysisTaskMapper mapper = mock(AnalysisTaskMapper.class);
    when(mapper.findOwned("task-1", "tenant-b", "user-b")).thenReturn(null);
    service =
        new AnalysisTaskServiceImpl(
            mapper,
            new JsonCodec(),
            mock(ResumeStorageService.class),
            mock(JobFavoriteService.class));

    assertThrows(
        IllegalArgumentException.class, () -> service.cancel("task-1", "tenant-b", "user-b"));

    verify(mapper, never()).markCancelled(anyString());
  }

  /**
   * 验证 AnalysisTaskService 的输入校验与拒绝边界。
   */
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

  /**
   * 验证任务。
   *
   * @param taskId 任务标识
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param type 类型
   * @param resourceKey 资源键
   * @param status 状态
   * @return 测试任务
   */
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
