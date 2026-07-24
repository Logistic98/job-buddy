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

class SystemSettingsServiceHealthMonitorTest {
  private static final JsonCodec JSON = new JsonCodec();

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

  @SuppressWarnings("unchecked")
  private Map<String, Object> statuses(Map<String, Object> settings) {
    return (Map<String, Object>) settings.get("serviceStatuses");
  }

  @SuppressWarnings("unchecked")
  private int historySize(Map<String, Object> statuses, String serviceId) {
    Map<String, Object> status = (Map<String, Object>) statuses.get(serviceId);
    return ((List<Map<String, Object>>) status.get("history")).size();
  }
}
