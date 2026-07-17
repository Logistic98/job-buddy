package com.jobbuddy.backend.modules.system.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.modules.system.dto.request.SystemMemoryRequest;
import com.jobbuddy.backend.modules.system.dto.response.SystemMemoryResponse;
import com.jobbuddy.backend.modules.system.mapper.SystemSettingsMapper;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SystemSettingsMemoryTest {

  @Test
  void autoMemoryShouldStayDisabledByDefault() {
    SystemSettingsMapper mapper = statefulMapper(new LinkedHashMap<String, String>());
    SystemSettingsServiceImpl service = newService(mapper);

    service.writeLocalMemory("tenant-a", "user-a", "preference", "我希望做后端", "chat");

    verify(mapper, never()).upsertSetting(anyString(), anyString(), anyString());
  }

  @Test
  void autoMemoryShouldPersistShortStableSignalsAndDedupe() {
    Map<String, String> stored = memoryEnabledState();
    SystemSettingsServiceImpl service = newService(statefulMapper(stored));

    service.writeLocalMemory("tenant-a", "user-a", "preference", "我希望做后端", "chat");
    service.writeLocalMemory("tenant-a", "user-a", "constraint", "排除外包岗位", "chat");
    service.writeLocalMemory("tenant-a", "user-a", "preference", "我希望做后端", "chat");

    List<SystemMemoryResponse> items = service.listMemories("tenant-a", "user-a");
    assertEquals(2, items.size());
    assertEquals("preference", items.get(0).getType());
    assertEquals("我希望做后端", items.get(0).getContent());
    assertEquals("constraint", items.get(1).getType());
    assertEquals("排除外包岗位", items.get(1).getContent());
  }

  @Test
  void autoMemoryShouldRejectUnclassifiedTypesAndTinyContent() {
    Map<String, String> stored = memoryEnabledState();
    SystemSettingsMapper mapper = statefulMapper(stored);
    SystemSettingsServiceImpl service = newService(mapper);

    service.writeLocalMemory("tenant-a", "user-a", "conversation", "帮我分析这个岗位", "chat");
    service.writeLocalMemory("tenant-a", "user-a", "preference", "喜欢", "chat");

    assertTrue(service.listMemories("tenant-a", "user-a").isEmpty());
  }

  @Test
  void memoriesMustBeIsolatedAcrossTenantAndUserMatrix() {
    Map<String, String> stored = memoryEnabledState();
    SystemSettingsServiceImpl service = newService(statefulMapper(stored));
    SystemMemoryRequest item = new SystemMemoryRequest();
    item.setType("preference");
    item.setContent("只属于 tenant-a/user-a");

    service.addMemory("tenant-a", "user-a", item);

    assertEquals(1, service.listMemories("tenant-a", "user-a").size());
    assertTrue(service.listMemories("tenant-a", "user-b").isEmpty());
    assertTrue(service.listMemories("tenant-b", "user-a").isEmpty());
  }

  @Test
  void unownedGlobalMemoryItemsMustNotBeReturnedToAnyUser() {
    Map<String, String> stored = new LinkedHashMap<String, String>();
    stored.put(
        key("global", "settings"),
        "{\"memory\":{\"enabled\":true,\"autoSaveChat\":false,\"autoUseMemory\":true,\"maxItems\":200,"
            + "\"items\":[{\"id\":\"unowned\",\"type\":\"preference\",\"content\":\"未归属的全局隐私\"}]}}");
    SystemSettingsServiceImpl service = newService(statefulMapper(stored));

    assertTrue(service.listMemories("tenant-a", "user-a").isEmpty());
    assertTrue(service.listMemories("tenant-b", "user-b").isEmpty());
  }

  private SystemSettingsServiceImpl newService(SystemSettingsMapper mapper) {
    return new SystemSettingsServiceImpl(
        new AgentServiceProperties(), new JobBuddyProperties(), mapper);
  }

  private Map<String, String> memoryEnabledState() {
    Map<String, String> stored = new LinkedHashMap<String, String>();
    stored.put(
        key("global", "settings"),
        "{\"memory\":{\"enabled\":true,\"autoSaveChat\":true,\"autoUseMemory\":true,\"maxItems\":200,\"items\":[]}}");
    return stored;
  }

  private SystemSettingsMapper statefulMapper(final Map<String, String> stored) {
    SystemSettingsMapper mapper = mock(SystemSettingsMapper.class);
    when(mapper.listBlacklistItems()).thenReturn(Collections.<Map<String, Object>>emptyList());
    when(mapper.findSettingJson(anyString(), anyString()))
        .thenAnswer(
            invocation ->
                stored.get(
                    key(
                        invocation.getArgument(0, String.class),
                        invocation.getArgument(1, String.class))));
    when(mapper.upsertSetting(anyString(), anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              stored.put(
                  key(
                      invocation.getArgument(0, String.class),
                      invocation.getArgument(1, String.class)),
                  invocation.getArgument(2, String.class));
              return 1;
            });
    return mapper;
  }

  private String key(String scope, String settingKey) {
    return scope + "\u0000" + settingKey;
  }
}
