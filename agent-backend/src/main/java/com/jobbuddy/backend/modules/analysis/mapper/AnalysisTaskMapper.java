package com.jobbuddy.backend.modules.analysis.mapper;

import com.jobbuddy.backend.modules.analysis.entity.AnalysisTask;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 映射分析任务数据记录。
 */
public interface AnalysisTaskMapper {
  /**
   * 新增分析任务。
   *
   * @param task 任务
   * @return 受影响的记录数
   */
  int insert(AnalysisTask task);

  /**
   * 查找已授权。
   *
   * @param taskId 任务标识
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 已授权
   */
  AnalysisTask findOwned(
      @Param("taskId") String taskId,
      @Param("tenantId") String tenantId,
      @Param("userId") String userId);

  /**
   * 查找最近。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param taskType 任务类型
   * @param resourceKey 资源键
   * @return 最近
   */
  AnalysisTask findLatest(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("taskType") String taskType,
      @Param("resourceKey") String resourceKey);

  /**
   * 查找活动。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param taskType 任务类型
   * @param resourceKey 资源键
   * @return 活动
   */
  AnalysisTask findActive(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("taskType") String taskType,
      @Param("resourceKey") String resourceKey);

  /**
   * 按标识查询记录。
   *
   * @param taskId 任务标识
   * @return 按标识查询到的记录
   */
  AnalysisTask findById(@Param("taskId") String taskId);

  /**
   * 查找可恢复任务。
   *
   * @return 可恢复异常
   */
  List<AnalysisTask> findRecoverable();

  /**
   * 将任务标记为运行中。
   *
   * @param taskId 任务标识
   * @param stage 阶段
   * @param message 消息内容
   * @return 受影响的记录数
   */
  int markRunning(
      @Param("taskId") String taskId,
      @Param("stage") String stage,
      @Param("message") String message);

  /**
   * 更新任务进度。
   *
   * @param taskId 任务标识
   * @param stage 阶段
   * @param message 消息内容
   * @return 受影响的记录数
   */
  int updateProgress(
      @Param("taskId") String taskId,
      @Param("stage") String stage,
      @Param("message") String message);

  /**
   * 更新任务增量结果。
   *
   * @param taskId 任务标识
   * @param stage 阶段
   * @param message 消息内容
   * @param partialResultJson 部分结果 JSON
   * @return 受影响的记录数
   */
  int updatePartialResult(
      @Param("taskId") String taskId,
      @Param("stage") String stage,
      @Param("message") String message,
      @Param("partialResultJson") String partialResultJson);

  /**
   * 将任务标记为成功。
   *
   * @param taskId 任务标识
   * @param resultJson 结果 JSON
   * @return 受影响的记录数
   */
  int markSucceeded(@Param("taskId") String taskId, @Param("resultJson") String resultJson);

  /**
   * 标记失败。
   *
   * @param taskId 任务标识
   * @param errorMessage 错误消息
   * @return 失败
   */
  int markFailed(@Param("taskId") String taskId, @Param("errorMessage") String errorMessage);

  /**
   * 将任务标记为已取消。
   *
   * @param taskId 任务标识
   * @return 受影响的记录数
   */
  int markCancelled(@Param("taskId") String taskId);
}
