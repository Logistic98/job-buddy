package com.jobbuddy.backend.modules.system.service;

import com.jobbuddy.backend.modules.system.dto.request.SystemMemoryRequest;
import com.jobbuddy.backend.modules.system.dto.request.SystemSettingsRequest;
import com.jobbuddy.backend.modules.system.dto.response.ServiceStatusesResponse;
import com.jobbuddy.backend.modules.system.dto.response.SystemMemoryResponse;
import com.jobbuddy.backend.modules.system.dto.response.SystemSettingsResponse;
import java.util.List;
import java.util.Map;

/**
 * 管理平台运行参数、健康状态、用户记忆操作与岗位黑名单策略。
 *
 * <p>全局设置只保存策略，记忆正文始终由 agent-memory 按租户和用户存储。
 */
public interface SystemSettingsService {
  /**
   * 获取当前平台运行设置。
   *
   * @return 设置
   */
  SystemSettingsResponse getSettings();

  /**
   * 刷新服务状态。
   *
   * @return 最新服务状态列表
   */
  ServiceStatusesResponse refreshServiceStatuses();

  /**
   * 保存并应用平台运行设置。
   *
   * @param request 请求对象
   * @return 保存后的设置
   */
  SystemSettingsResponse saveSettings(SystemSettingsRequest request);

  /**
   * 恢复工作区默认值。
   *
   * @return 恢复后的工作区默认设置
   */
  SystemSettingsResponse restoreWorkspaceDefaults();

  /**
   * 查询记忆列表。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 记忆列表
   */
  List<SystemMemoryResponse> listMemories(String tenantId, String userId);

  /**
   * 新增记忆。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param request 请求对象
   * @return 新增后的记忆
   */
  SystemMemoryResponse addMemory(String tenantId, String userId, SystemMemoryRequest request);

  /**
   * 写入自动记忆候选，但不在 Backend 创建第二套正文存储。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param type 类型
   * @param content 内容
   * @param source 源数据
   */
  void writeLocalMemory(String tenantId, String userId, String type, String content, String source);

  /**
   * 删除记忆。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param memoryId 记忆标识
   */
  void deleteMemory(String tenantId, String userId, String memoryId);

  /**
   * 清理记忆。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 记忆清理结果
   */
  int clearMemories(String tenantId, String userId);

  /**
   * 检索本地数据记忆。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param query 查询条件
   * @param limit 数量上限
   * @return 本地记忆搜索结果
   */
  List<SystemMemoryResponse> searchLocalMemories(
      String tenantId, String userId, String query, int limit);

  /**
   * 查询黑名单数据项列表。
   *
   * @return 黑名单数据项列表
   */
  List<SystemSettingsResponse.Item> listBlacklistItems();

  // 岗位对象来自 Boss/Runtime，字段不稳定，保留在明确的外部 JSON 边界。
  /**
   * 判断岗位是否命中黑名单。
   *
   * @param job 岗位
   * @return 岗位是否命中黑名单
   */
  boolean isBlacklistedJob(Map<String, Object> job);

  /**
   * 筛除命中黑名单的岗位。
   *
   * @param jobs 岗位列表
   * @return 过滤后的岗位列表
   */
  List<Map<String, Object>> filterBlacklistedJobs(List<Map<String, Object>> jobs);
}
