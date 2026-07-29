package com.jobbuddy.backend.modules.system.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.system.client.AgentMemoryClient;
import com.jobbuddy.backend.modules.system.mapper.SystemSettingsMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 验证 SystemSettingsServiceHealthMonitor 的核心行为、异常路径与边界条件。
 */
class SystemSettingsServiceHealthMonitorTest {
  private static final JsonCodec JSON = new JsonCodec();

  /**
   * 验证读取设置时保留服务健康历史。
   */
  @Test
  void keepsHealthHistoryAcrossSettingsReads() {
    SystemSettingsMapper mapper = mock(SystemSettingsMapper.class);
    when(mapper.findSettingJson("global", "settings")).thenReturn(null);
    when(mapper.listBlacklistItems()).thenReturn(List.of());
    SystemSettingsServiceImpl service =
        new SystemSettingsServiceImpl(
            emptyServiceProperties(),
            new JobBuddyProperties(),
            mapper,
            mock(AgentMemoryClient.class));

    service.refreshServiceStatuses();
    Map<String, Object> secondRefresh = JSON.toMap(service.refreshServiceStatuses());

    assertEquals(2, historySize(secondRefresh, "runtime"));
    assertEquals(2, historySize(secondRefresh, "sandbox"));
    Map<String, Object> settings = JSON.toMap(service.getSettings());
    assertEquals(2, historySize(statuses(settings), "runtime"));
  }

  /**
   * 验证 SystemSettingsServiceHealthMonitor 的数量、长度与分页边界。
   */
  @Test
  void scheduledSamplesAreLimitedToRecentHistory() {
    SystemSettingsServiceImpl service =
        new SystemSettingsServiceImpl(
            emptyServiceProperties(),
            new JobBuddyProperties(),
            mock(SystemSettingsMapper.class),
            mock(AgentMemoryClient.class));

    Map<String, Object> statuses = null;
    for (int index = 0; index < 65; index++)
      statuses = JSON.toMap(service.refreshServiceStatuses());

    assertEquals(60, historySize(statuses, "runtime"));
  }

  /**
   * 验证 Sandbox 使用真实 Runtime readiness，其他服务仍使用进程健康端点。
   */
  @Test
  void usesRuntimeReadinessForSandboxOnly() {
    assertEquals(
        "http://agent-sandbox:8061/ready",
        ServiceHealthMonitor.healthUrl("sandbox", "http://agent-sandbox:8061/"));
    assertEquals(
        "http://agent-runtime:8010/health",
        ServiceHealthMonitor.healthUrl("runtime", "http://agent-runtime:8010"));
  }

  /**
   * 验证空值服务配置属性。
   *
   * @return emptyService 配置属性
   */
  private AgentServiceProperties emptyServiceProperties() {
    AgentServiceProperties properties = new AgentServiceProperties();
    properties.setIntentUrl("");
    properties.setRuntimeUrl("");
    properties.setMemoryUrl("");
    properties.setToolUrl("");
    properties.setEvalUrl("");
    properties.setSandboxUrl("");
    return properties;
  }

  /**
   * 验证状态列表。
   *
   * @param settings 设置
   * @return 服务状态列表
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> statuses(Map<String, Object> settings) {
    return (Map<String, Object>) settings.get("serviceStatuses");
  }

  /**
   * 读取指定服务的健康历史长度。
   *
   * @param statuses 状态列表
   * @param serviceId 服务标识
   * @return history 大小
   */
  @SuppressWarnings("unchecked")
  private int historySize(Map<String, Object> statuses, String serviceId) {
    Map<String, Object> status = (Map<String, Object>) statuses.get(serviceId);
    return ((List<Map<String, Object>>) status.get("history")).size();
  }
}
