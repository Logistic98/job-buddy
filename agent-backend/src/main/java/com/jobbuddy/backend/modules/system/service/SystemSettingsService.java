package com.jobbuddy.backend.modules.system.service;

import com.jobbuddy.backend.modules.system.dto.request.SystemMemoryRequest;
import com.jobbuddy.backend.modules.system.dto.request.SystemSettingsRequest;
import com.jobbuddy.backend.modules.system.dto.response.ServiceStatusesResponse;
import com.jobbuddy.backend.modules.system.dto.response.SystemMemoryResponse;
import com.jobbuddy.backend.modules.system.dto.response.SystemSettingsResponse;
import java.util.List;
import java.util.Map;

public interface SystemSettingsService {
  SystemSettingsResponse getSettings();

  ServiceStatusesResponse refreshServiceStatuses();

  SystemSettingsResponse saveSettings(SystemSettingsRequest request);

  SystemSettingsResponse restoreWorkspaceDefaults();

  List<SystemMemoryResponse> listMemories(String tenantId, String userId);

  SystemMemoryResponse addMemory(String tenantId, String userId, SystemMemoryRequest request);

  void writeLocalMemory(String tenantId, String userId, String type, String content, String source);

  void deleteMemory(String tenantId, String userId, String memoryId);

  int clearMemories(String tenantId, String userId);

  List<SystemMemoryResponse> searchLocalMemories(
      String tenantId, String userId, String query, int limit);

  List<SystemSettingsResponse.Item> listBlacklistItems();

  // 岗位对象来自 Boss/Runtime，字段不稳定，保留在明确的外部 JSON 边界。
  boolean isBlacklistedJob(Map<String, Object> job);

  List<Map<String, Object>> filterBlacklistedJobs(List<Map<String, Object>> jobs);
}
