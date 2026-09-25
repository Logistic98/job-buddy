package com.jobbuddy.backend.modules.analysis.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.analysis.dto.AnalysisPartialResult;
import com.jobbuddy.backend.modules.analysis.entity.AnalysisTask;
import com.jobbuddy.backend.modules.analysis.mapper.AnalysisTaskMapper;
import com.jobbuddy.backend.modules.job.dto.command.JobFavoriteSaveCommand;
import com.jobbuddy.backend.modules.job.dto.response.JobFavoriteResponse;
import com.jobbuddy.backend.modules.job.service.JobFavoriteService;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;

class AnalysisTaskExecutionTest {
  private final JsonCodec json = new JsonCodec();
  private final AnalysisTaskMapper mapper = mock(AnalysisTaskMapper.class);
  private final ResumeStorageService resumes = mock(ResumeStorageService.class);
  private final JobFavoriteService jobs = mock(JobFavoriteService.class);
  private final AnalysisTaskServiceImpl service =
      new AnalysisTaskServiceImpl(mapper, json, resumes, jobs);

  @AfterEach
  void closeExecutors() {
    service.shutdown();
  }

  @Test
  void newResumeTaskPersistsOwnerAndPublishesIncrementalResult() throws Exception {
    AtomicReference<AnalysisTask> inserted = new AtomicReference<>();
    CountDownLatch completed = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              inserted.set(invocation.getArgument(0));
              return 1;
            })
        .when(mapper)
        .insert(any(AnalysisTask.class));
    when(mapper.findById(anyString())).thenAnswer(invocation -> inserted.get());
    when(mapper.markRunning(anyString(), anyString(), anyString())).thenReturn(1);
    when(mapper.updateProgress(anyString(), anyString(), anyString())).thenReturn(1);
    when(mapper.updatePartialResult(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(1);
    doAnswer(
            invocation -> {
              completed.countDown();
              return 1;
            })
        .when(mapper)
        .markSucceeded(anyString(), anyString());
    ResumeRecord record = new ResumeRecord();
    when(resumes.analyzeIncrementally(eq("resume"), eq(""), eq("tenant"), eq("owner"), any()))
        .thenAnswer(
            invocation -> {
              Consumer<AnalysisPartialResult> publish = invocation.getArgument(4);
              publish.accept(
                  new AnalysisPartialResult(
                      "skills", "ready", json.toTree(Map.of("skills", "Java"))));
              return record;
            });

    service.startResume("tenant", "owner", "resume", null);

    assertTrue(
        completed.await(3, TimeUnit.SECONDS), "analysis must reach a persisted terminal result");
    assertEquals("tenant", inserted.get().getTenantId());
    assertEquals("owner", inserted.get().getUserId());
    assertEquals("resume", inserted.get().getResourceKey());
    verify(resumes).get("resume", "tenant", "owner");
    verify(mapper)
        .updatePartialResult(
            eq(inserted.get().getTaskId()), eq("partial_skills"), eq("ready"), contains("Java"));
    verify(mapper, never()).markFailed(anyString(), anyString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"resume", "favorite_job", "unsupported"})
  void recoveredTasksPersistFailureInsteadOfDisappearing(String type) throws Exception {
    AnalysisTask task = task(type);
    CountDownLatch failed = new CountDownLatch(1);
    when(mapper.findRecoverable()).thenReturn(List.of(task));
    when(mapper.findById("task")).thenReturn(task);
    when(mapper.markRunning(anyString(), anyString(), anyString())).thenReturn(1);
    when(mapper.updateProgress(anyString(), anyString(), anyString())).thenReturn(1);
    when(resumes.analyzeIncrementally(anyString(), anyString(), anyString(), anyString(), any()))
        .thenThrow(new IllegalStateException("model unavailable"));
    when(jobs.analyzeJobIncrementally(anyString(), any(), anyString(), any()))
        .thenThrow(new IllegalStateException("model unavailable"));
    doAnswer(
            invocation -> {
              failed.countDown();
              return 1;
            })
        .when(mapper)
        .markFailed(eq("task"), anyString());

    service.recoverTasks();

    assertTrue(failed.await(3, TimeUnit.SECONDS));
    verify(mapper)
        .markFailed(eq("task"), contains(type.equals("unsupported") ? "不支持" : "model unavailable"));
    verify(mapper, never()).markSucceeded(anyString(), anyString());
  }

  @Test
  void recoveredFavoriteTaskSavesTheActualAnalysisResponse() throws Exception {
    AnalysisTask task = task("favorite_job");
    CountDownLatch completed = new CountDownLatch(1);
    when(mapper.findRecoverable()).thenReturn(List.of(task));
    when(mapper.findById("task")).thenReturn(task);
    when(mapper.markRunning(anyString(), anyString(), anyString())).thenReturn(1);
    when(mapper.updateProgress(anyString(), anyString(), anyString())).thenReturn(1);
    when(jobs.analyzeJobIncrementally(eq("owner"), any(), eq("resume"), any()))
        .thenReturn(new JobFavoriteResponse(json.toTree(Map.of("score", 90))));
    doAnswer(
            invocation -> {
              completed.countDown();
              return 1;
            })
        .when(mapper)
        .markSucceeded(eq("task"), anyString());

    service.recoverTasks();

    assertTrue(completed.await(3, TimeUnit.SECONDS));
    verify(mapper).markSucceeded("task", "{\"score\":90}");
  }

  @Test
  void duplicateInsertReusesConcurrentOwnedTask() {
    AnalysisTask concurrent = task("favorite_job");
    when(mapper.findActive("tenant", "owner", "favorite_job", "job")).thenReturn(null, concurrent);
    doThrow(new DuplicateKeyException("duplicate")).when(mapper).insert(any(AnalysisTask.class));
    var result =
        service.startFavoriteJob(
            "tenant",
            "owner",
            JobFavoriteSaveCommand.from(json.toTree(Map.of("jobId", "job"))),
            null);
    assertEquals("task", result.getTaskId());
    verifyNoInteractions(jobs);
  }

  @Test
  void shutdownRejectsQueuedWorkWithPersistedFailure() {
    AnalysisTask task = task("resume");
    when(mapper.findRecoverable()).thenReturn(List.of(task));
    service.shutdown();
    service.recoverTasks();
    verify(mapper).markFailed(eq("task"), contains("队列繁忙"));
    verifyNoInteractions(resumes, jobs);
  }

  @Test
  void terminalTaskIsNeverReexecuted() throws Exception {
    AnalysisTask task = task("resume");
    task.setStatus("succeeded");
    CountDownLatch loaded = new CountDownLatch(1);
    when(mapper.findRecoverable()).thenReturn(List.of(task));
    when(mapper.findById("task"))
        .thenAnswer(
            invocation -> {
              loaded.countDown();
              return task;
            });
    service.recoverTasks();
    assertTrue(loaded.await(3, TimeUnit.SECONDS));
    verify(mapper, never()).markRunning(anyString(), anyString(), anyString());
    verifyNoInteractions(resumes, jobs);
  }

  private AnalysisTask task(String type) {
    AnalysisTask task = new AnalysisTask();
    task.setTaskId("task");
    task.setTaskType(type);
    task.setTenantId("tenant");
    task.setUserId("owner");
    task.setResourceKey("resource");
    task.setStatus("queued");
    task.setRequestJson(
        "{\"resumeId\":\"resume\",\"sessionId\":\"session\",\"job\":{\"jobId\":\"job\"}}");
    return task;
  }
}
