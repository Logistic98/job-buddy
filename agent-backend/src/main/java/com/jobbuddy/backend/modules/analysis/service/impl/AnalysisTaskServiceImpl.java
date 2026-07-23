package com.jobbuddy.backend.modules.analysis.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobbuddy.backend.common.security.AuthenticationScope;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.common.web.MdcContextFilter;
import com.jobbuddy.backend.modules.analysis.dto.AnalysisPartialResult;
import com.jobbuddy.backend.modules.analysis.dto.AnalysisTaskResponse;
import com.jobbuddy.backend.modules.analysis.entity.AnalysisTask;
import com.jobbuddy.backend.modules.analysis.mapper.AnalysisTaskMapper;
import com.jobbuddy.backend.modules.analysis.service.AnalysisTaskService;
import com.jobbuddy.backend.modules.job.dto.command.JobFavoriteSaveCommand;
import com.jobbuddy.backend.modules.job.dto.response.JobFavoriteResponse;
import com.jobbuddy.backend.modules.job.service.JobFavoriteService;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class AnalysisTaskServiceImpl implements AnalysisTaskService {
  public static final String TYPE_RESUME = "resume";
  public static final String TYPE_FAVORITE_JOB = "favorite_job";
  private static final Logger log = LoggerFactory.getLogger(AnalysisTaskServiceImpl.class);
  private static final long STREAM_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(15);

  private final AnalysisTaskMapper mapper;
  private final JsonCodec jsonCodec;
  private final ResumeStorageService resumeStorageService;
  private final JobFavoriteService jobFavoriteService;
  private final ExecutorService taskExecutor =
      new ThreadPoolExecutor(
          2,
          8,
          60L,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<Runnable>(128),
          namedFactory("analysis-task"),
          new ThreadPoolExecutor.AbortPolicy());
  private final ExecutorService streamExecutor =
      new ThreadPoolExecutor(
          2,
          32,
          60L,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<Runnable>(256),
          namedFactory("analysis-stream"),
          new ThreadPoolExecutor.AbortPolicy());

  public AnalysisTaskServiceImpl(
      AnalysisTaskMapper mapper,
      JsonCodec jsonCodec,
      ResumeStorageService resumeStorageService,
      JobFavoriteService jobFavoriteService) {
    this.mapper = mapper;
    this.jsonCodec = jsonCodec;
    this.resumeStorageService = resumeStorageService;
    this.jobFavoriteService = jobFavoriteService;
  }

  @Override
  public AnalysisTaskResponse startResume(
      String tenantId, String userId, String resumeId, String sessionId) {
    requireText(resumeId, "缺少简历标识");
    resumeStorageService.get(resumeId, tenantId, userId);
    ResumeTaskRequest request = new ResumeTaskRequest(resumeId, sessionId == null ? "" : sessionId);
    return createOrReuse(tenantId, userId, TYPE_RESUME, resumeId, request);
  }

  @Override
  public AnalysisTaskResponse startFavoriteJob(
      String tenantId, String userId, JobFavoriteSaveCommand command, String resumeId) {
    JsonNode snapshot = command == null ? jsonCodec.toTree(null) : command.snapshot();
    String resourceKey = jobKey(snapshot);
    requireText(resourceKey, "缺少收藏岗位标识");
    FavoriteJobTaskRequest request =
        new FavoriteJobTaskRequest(snapshot, resumeId == null ? "" : resumeId);
    return createOrReuse(tenantId, userId, TYPE_FAVORITE_JOB, resourceKey, request);
  }

  @Override
  public AnalysisTaskResponse getOwned(String taskId, String tenantId, String userId) {
    AnalysisTask task = mapper.findOwned(taskId, tenantId, userId);
    if (task == null) throw new IllegalArgumentException("分析任务不存在");
    return AnalysisTaskResponse.from(task, jsonCodec);
  }

  @Override
  public AnalysisTaskResponse findLatest(
      String tenantId, String userId, String taskType, String resourceKey) {
    validateType(taskType);
    requireText(resourceKey, "缺少分析资源标识");
    return AnalysisTaskResponse.from(
        mapper.findLatest(tenantId, userId, taskType, resourceKey), jsonCodec);
  }

  @Override
  public SseEmitter stream(String taskId, String tenantId, String userId) {
    AnalysisTask owned = mapper.findOwned(taskId, tenantId, userId);
    if (owned == null) throw new IllegalArgumentException("分析任务不存在");
    final SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
    try {
      streamExecutor.submit(withInheritedMdc(() -> streamLoop(taskId, tenantId, userId, emitter)));
    } catch (RuntimeException rejected) {
      emitter.completeWithError(new IllegalStateException("分析事件订阅繁忙，请稍后重试", rejected));
    }
    return emitter;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void recoverTasks() {
    try {
      for (AnalysisTask task : mapper.findRecoverable()) submit(task.getTaskId());
    } catch (RuntimeException unavailable) {
      // 部分轻量测试上下文会关闭 Flyway 且不初始化业务表；不能因此阻断整个应用上下文。
      // 正常环境由 Flyway 在 ApplicationReadyEvent 前创建 analysis_task。
      log.debug("跳过异步分析任务恢复，任务表当前不可用: {}", unavailable.getMessage());
    }
  }

  @PreDestroy
  public void shutdown() {
    taskExecutor.shutdownNow();
    streamExecutor.shutdownNow();
  }

  private AnalysisTaskResponse createOrReuse(
      String tenantId, String userId, String type, String resourceKey, Object request) {
    AnalysisTask active = mapper.findActive(tenantId, userId, type, resourceKey);
    if (active != null) return AnalysisTaskResponse.from(active, jsonCodec);
    AnalysisTask task = new AnalysisTask();
    task.setTaskId("analysis_" + UUID.randomUUID().toString().replace("-", ""));
    task.setTenantId(tenantId);
    task.setUserId(userId);
    task.setTaskType(type);
    task.setResourceKey(resourceKey);
    task.setStatus("queued");
    task.setStage("queued");
    task.setMessage("分析任务已创建，正在排队");
    task.setRequestJson(jsonCodec.toJson(request));
    try {
      mapper.insert(task);
    } catch (DuplicateKeyException duplicate) {
      AnalysisTask concurrent = mapper.findActive(tenantId, userId, type, resourceKey);
      if (concurrent != null) return AnalysisTaskResponse.from(concurrent, jsonCodec);
      throw duplicate;
    }
    submit(task.getTaskId());
    return AnalysisTaskResponse.from(mapper.findById(task.getTaskId()), jsonCodec);
  }

  private void submit(String taskId) {
    try {
      taskExecutor.submit(withInheritedMdc(() -> execute(taskId)));
    } catch (RuntimeException rejected) {
      mapper.markFailed(taskId, "分析任务队列繁忙，请稍后重新发起");
    }
  }

  private void execute(String taskId) {
    AnalysisTask task = mapper.findById(taskId);
    if (task == null || task.isTerminal()) return;
    if (mapper.markRunning(taskId, "preparing", "正在准备分析上下文") != 1) return;
    bindTaskMdcContext(task, "analysis:" + task.getTaskType(), "analysis-" + taskId);
    AuthenticationScope.set(task.getTenantId(), task.getUserId());
    try {
      mapper.updateProgress(taskId, "analyzing", "正在调用模型生成分析报告");
      JsonNode request = jsonCodec.readTree(task.getRequestJson());
      JsonNode result;
      if (TYPE_RESUME.equals(task.getTaskType())) {
        String resumeId = text(request, "resumeId");
        ResumeRecord record =
            resumeStorageService.analyzeIncrementally(
                resumeId,
                text(request, "sessionId"),
                task.getTenantId(),
                task.getUserId(),
                partial -> publishPartial(taskId, partial));
        result = jsonCodec.toTree(resumeStorageService.summarize(record));
      } else if (TYPE_FAVORITE_JOB.equals(task.getTaskType())) {
        JobFavoriteResponse response =
            jobFavoriteService.analyzeJobIncrementally(
                task.getUserId(),
                JobFavoriteSaveCommand.from(request.path("job")),
                text(request, "resumeId"),
                partial -> publishPartial(taskId, partial));
        result = response.value();
      } else {
        throw new IllegalArgumentException("不支持的分析任务类型: " + task.getTaskType());
      }
      mapper.updateProgress(taskId, "saving", "正在保存分析结果");
      mapper.markSucceeded(taskId, jsonCodec.toJson(result));
    } catch (Exception error) {
      String message = safeMessage(error);
      mapper.markFailed(taskId, message);
      log.warn(
          "异步分析任务失败 taskId={}, type={}, resourceKey={}: {}",
          taskId,
          task.getTaskType(),
          task.getResourceKey(),
          message);
    } finally {
      AuthenticationScope.clear();
      MDC.clear();
    }
  }

  private void streamLoop(String taskId, String tenantId, String userId, SseEmitter emitter) {
    boolean contextBound = false;
    long lastVersion = Long.MIN_VALUE;
    long lastHeartbeat = 0L;
    try {
      while (!Thread.currentThread().isInterrupted()) {
        AnalysisTask task = mapper.findOwned(taskId, tenantId, userId);
        if (!contextBound && task != null) {
          bindTaskMdcContext(
              task, "analysis-stream:" + task.getTaskType(), "analysis-stream-" + taskId);
          contextBound = true;
        }
        if (task == null) throw new IllegalArgumentException("分析任务不存在");
        if (task.getVersion() != lastVersion) {
          AnalysisTaskResponse payload = AnalysisTaskResponse.from(task, jsonCodec);
          if (lastVersion == Long.MIN_VALUE) {
            send(emitter, "snapshot", payload);
          } else if (task.isTerminal()) {
            if ("succeeded".equals(task.getStatus())) send(emitter, "result", payload);
            else send(emitter, "error", payload);
            send(emitter, "done", payload);
            emitter.complete();
            return;
          } else if (!payload.getPartialResult().isEmpty()) {
            // 部分结果与阶段必须在同一个事件中原子到达。若先发 progress 再发 partial_result，
            // 前端会短暂出现“已生成”但报告仍为空的矛盾画面。
            send(emitter, "partial_result", payload);
          } else {
            send(emitter, "progress", payload);
          }
          lastVersion = task.getVersion();
        }
        long now = System.currentTimeMillis();
        if (now - lastHeartbeat >= 15000L) {
          send(emitter, "heartbeat", java.util.Collections.singletonMap("taskId", taskId));
          lastHeartbeat = now;
        }
        Thread.sleep(500L);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      emitter.complete();
    } catch (Exception disconnected) {
      try {
        emitter.complete();
      } catch (Exception ignored) {
      }
      log.debug("分析 SSE 订阅结束 taskId={}: {}", taskId, disconnected.getMessage());
    }
  }

  private void publishPartial(String taskId, AnalysisPartialResult partial) {
    mapper.updatePartialResult(
        taskId,
        "partial_" + partial.getSection(),
        partial.getMessage(),
        jsonCodec.toJson(partial.getPayload()));
  }

  private Runnable withInheritedMdc(Runnable task) {
    final Map<String, String> parentContext = copyCurrentMdc();
    return () -> {
      Map<String, String> inherited =
          parentContext == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parentContext);
      Map<String, String> backup = copyCurrentMdc();
      try {
        inherited.forEach(
            (key, value) -> {
              if (value != null) {
                MDC.put(key, value);
              }
            });
        task.run();
      } finally {
        MDC.clear();
        if (backup != null) {
          backup.forEach(
              (key, value) -> {
                if (value != null) {
                  MDC.put(key, value);
                }
              });
        }
      }
    };
  }

  private void bindTaskMdcContext(AnalysisTask task, String actor, String requestId) {
    if (task == null) return;
    String safeRequestId = safeText(requestId);
    String safeTaskId = safeText(task.getTaskId());
    String resolvedRequestId = safeRequestId == null ? safeTaskId : safeRequestId;
    String safeUserId = safeText(task.getUserId()) == null ? "-" : safeText(task.getUserId());
    String safeTenantId = safeText(task.getTenantId());
    String safeSessionId = safeUserId;
    if (safeTenantId != null) {
      safeSessionId = safeTenantId + ":" + safeUserId;
    }

    if (resolvedRequestId != null) {
      MDC.put(MdcContextFilter.REQUEST_ID, resolvedRequestId);
    }
    MDC.put(
        MdcContextFilter.SESSION_ID,
        safeSessionId == null || safeSessionId.isBlank() ? "analysis-session" : safeSessionId);
    MDC.put(MdcContextFilter.OPERATOR_ID, safeUserId);
    MDC.put(MdcContextFilter.RUN_ID, safeTaskId == null ? resolvedRequestId : safeTaskId);
    MDC.put("actor", actor == null ? "analysis-worker" : actor);
  }

  private Map<String, String> copyCurrentMdc() {
    Map<String, String> context = MDC.getCopyOfContextMap();
    return context == null ? null : new LinkedHashMap<>(context);
  }

  private void send(SseEmitter emitter, String event, Object data) throws IOException {
    emitter.send(SseEmitter.event().name(event).data(data));
  }

  private void validateType(String taskType) {
    if (!TYPE_RESUME.equals(taskType) && !TYPE_FAVORITE_JOB.equals(taskType)) {
      throw new IllegalArgumentException("不支持的分析任务类型");
    }
  }

  private String jobKey(JsonNode item) {
    for (String key :
        new String[] {"jobKey", "favoriteKey", "securityId", "id", "jobId", "encryptJobId"}) {
      String value = text(item, key);
      if (!value.isEmpty()) return value;
    }
    return "";
  }

  private String text(JsonNode object, String field) {
    if (object == null || object.isNull()) return "";
    JsonNode value = object.get(field);
    return value == null || value.isNull() ? "" : value.asText().trim();
  }

  private String string(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private String safeText(Object value) {
    String text = string(value);
    return text == null || text.isBlank() ? null : text;
  }

  private void requireText(String value, String message) {
    if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(message);
  }

  private String safeMessage(Throwable error) {
    String message = error == null ? "分析失败" : error.getMessage();
    return message == null || message.trim().isEmpty() ? "分析失败，请稍后重试" : message;
  }

  private record ResumeTaskRequest(String resumeId, String sessionId) {}

  private record FavoriteJobTaskRequest(JsonNode job, String resumeId) {}

  private static ThreadFactory namedFactory(final String prefix) {
    final AtomicInteger sequence = new AtomicInteger();
    return runnable -> {
      Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }
}
