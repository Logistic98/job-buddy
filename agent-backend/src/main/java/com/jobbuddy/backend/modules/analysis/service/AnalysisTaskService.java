package com.jobbuddy.backend.modules.analysis.service;

import com.jobbuddy.backend.modules.analysis.dto.AnalysisTaskResponse;
import com.jobbuddy.backend.modules.job.dto.command.JobFavoriteSaveCommand;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 管理与 SSE 观察连接解耦的用户级持久化分析任务。
 *
 * <p>关闭事件流不会取消任务，显式取消必须调用 {@link #cancel}。
 */
public interface AnalysisTaskService {
  String TYPE_RESUME = "resume";
  String TYPE_FAVORITE_JOB = "favorite_job";

  /**
   * 启动简历。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param resumeId 简历标识
   * @param sessionId 会话标识
   * @return 启动后的简历
   */
  AnalysisTaskResponse startResume(
      String tenantId, String userId, String resumeId, String sessionId);

  /**
   * 启动收藏岗位分析。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param command 业务命令
   * @param resumeId 简历标识
   * @return 启动后的收藏岗位岗位
   */
  AnalysisTaskResponse startFavoriteJob(
      String tenantId, String userId, JobFavoriteSaveCommand command, String resumeId);

  /**
   * 获取当前用户所属资源。
   *
   * @param taskId 任务标识
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 当前用户所属资源
   */
  AnalysisTaskResponse getOwned(String taskId, String tenantId, String userId);

  /**
   * 取消分析任务。
   *
   * @param taskId 任务标识
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 取消结果
   */
  AnalysisTaskResponse cancel(String taskId, String tenantId, String userId);

  /**
   * 取消指定资源当前仍在运行的分析任务。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param taskType 任务类型
   * @param resourceKey 资源标识
   */
  void cancelActiveResource(String tenantId, String userId, String taskType, String resourceKey);

  /**
   * 查询最新。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param taskType 任务类型
   * @param resourceKey 资源键
   * @return 最新
   */
  AnalysisTaskResponse findLatest(
      String tenantId, String userId, String taskType, String resourceKey);

  /**
   * 为已通过属主校验的任务打开有界事件流。
   *
   * @param taskId 任务标识
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 最终发送任务终态或明确流错误的事件发射器
   */
  SseEmitter stream(String taskId, String tenantId, String userId);
}
