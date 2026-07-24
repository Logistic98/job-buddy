package com.jobbuddy.backend.modules.system.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.modules.system.client.AgentMemoryClient;
import com.jobbuddy.backend.modules.system.dto.request.SystemMemoryRequest;
import com.jobbuddy.backend.modules.system.dto.response.SystemMemoryResponse;
import com.jobbuddy.backend.modules.system.mapper.SystemSettingsMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SystemSettingsMemoryTest {

  @Test
  void autoMemoryShouldStayDisabledByDefault() {
    AgentMemoryClient client = statefulClient();
    SystemSettingsServiceImpl service =
        newService(statefulMapper(new LinkedHashMap<String, String>()), client);

    service.writeLocalMemory("tenant-a", "user-a", "preference", "我希望做后端", "chat");

    verify(client, never()).create(anyString(), anyString(), any(SystemMemoryRequest.class));
  }

  @Test
  void autoMemoryShouldPersistStableSignalsAndDedupeInAgentMemory() {
    AgentMemoryClient client = statefulClient();
    SystemSettingsServiceImpl service = newService(statefulMapper(memoryEnabledState()), client);

    service.writeLocalMemory("tenant-a", "user-a", "preference", "我希望做后端", "chat");
    service.writeLocalMemory("tenant-a", "user-a", "constraint", "排除外包岗位", "chat");
    service.writeLocalMemory("tenant-a", "user-a", "preference", "我希望做后端", "chat");

    List<SystemMemoryResponse> items = service.listMemories("tenant-a", "user-a");
    assertEquals(2, items.size());
    assertEquals("constraint", items.get(0).getType());
    assertEquals("preference", items.get(1).getType());
  }

  @Test
  void autoMemoryShouldRejectUnclassifiedTypesAndTinyContent() {
    AgentMemoryClient client = statefulClient();
    SystemSettingsServiceImpl service = newService(statefulMapper(memoryEnabledState()), client);

    service.writeLocalMemory("tenant-a", "user-a", "conversation", "帮我分析这个岗位", "chat");
    service.writeLocalMemory("tenant-a", "user-a", "preference", "喜欢", "chat");

    assertTrue(service.listMemories("tenant-a", "user-a").isEmpty());
  }

  @Test
  void memoriesMustBeIsolatedAcrossTenantAndUserMatrix() {
    AgentMemoryClient client = statefulClient();
    SystemSettingsServiceImpl service = newService(statefulMapper(memoryEnabledState()), client);
    SystemMemoryRequest item = new SystemMemoryRequest();
    item.setType("preference");
    item.setContent("只属于 tenant-a/user-a");

    service.addMemory("tenant-a", "user-a", item);

    assertEquals(1, service.listMemories("tenant-a", "user-a").size());
    assertTrue(service.listMemories("tenant-a", "user-b").isEmpty());
    assertTrue(service.listMemories("tenant-b", "user-a").isEmpty());
  }

  @Test
  void legacyPlatformSettingItemsAreMigratedOnce() {
    Map<String, String> stored = memoryEnabledState();
    SystemSettingsMapper mapper = statefulMapper(stored);
    when(mapper.findSettingJson(anyString(), eq("memory")))
        .thenReturn(
            "{\"items\":[{\"type\":\"constraint\",\"content\":\"排除外包岗位\","
                + "\"source\":\"legacy\",\"enabled\":true}]}");
    AgentMemoryClient client = statefulClient();
    SystemSettingsServiceImpl service = newService(mapper, client);

    List<SystemMemoryResponse> items = service.listMemories("tenant-a", "user-a");

    assertEquals(1, items.size());
    assertEquals("排除外包岗位", items.get(0).getContent());
    verify(mapper).deleteSetting(anyString(), eq("memory"));
  }

  @Test
  void failedLegacyMigrationKeepsTheSourceRecordForRetry() {
    SystemSettingsMapper mapper = statefulMapper(memoryEnabledState());
    when(mapper.findSettingJson(anyString(), eq("memory")))
        .thenReturn(
            "{\"items\":[{\"type\":\"constraint\",\"content\":\"排除外包岗位\","
                + "\"source\":\"legacy\",\"enabled\":true}]}");
    AgentMemoryClient client = mock(AgentMemoryClient.class);
    when(client.list("tenant-a", "user-a")).thenReturn(Collections.emptyList());
    when(client.create(eq("tenant-a"), eq("user-a"), any(SystemMemoryRequest.class)))
        .thenThrow(new IllegalStateException("agent-memory unavailable"));
    SystemSettingsServiceImpl service = newService(mapper, client);

    assertThrows(IllegalStateException.class, () -> service.listMemories("tenant-a", "user-a"));

    verify(mapper, never()).deleteSetting(anyString(), eq("memory"));
  }

  private SystemSettingsServiceImpl newService(
      SystemSettingsMapper mapper, AgentMemoryClient client) {
    AgentServiceProperties properties = new AgentServiceProperties();
    properties.setMemoryUrl("http://127.0.0.1:8030");
    return new SystemSettingsServiceImpl(properties, new JobBuddyProperties(), mapper, client);
  }

  private Map<String, String> memoryEnabledState() {
    Map<String, String> stored = new LinkedHashMap<String, String>();
    stored.put(
        key("global", "settings"),
        "{\"memory\":{\"enabled\":true,\"autoSaveChat\":true,"
            + "\"autoUseMemory\":true,\"maxItems\":200,\"items\":[]}}");
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
    when(mapper.deleteSetting(anyString(), anyString()))
        .thenAnswer(
            invocation ->
                stored.remove(
                            key(
                                invocation.getArgument(0, String.class),
                                invocation.getArgument(1, String.class)))
                        == null
                    ? 0
                    : 1);
    return mapper;
  }

  private AgentMemoryClient statefulClient() {
    AgentMemoryClient client = mock(AgentMemoryClient.class);
    Map<String, List<SystemMemoryResponse>> stored =
        new LinkedHashMap<String, List<SystemMemoryResponse>>();
    AtomicInteger sequence = new AtomicInteger();
    when(client.list(anyString(), anyString()))
        .thenAnswer(
            invocation ->
                new ArrayList<SystemMemoryResponse>(
                    stored.getOrDefault(
                        owner(invocation.getArgument(0), invocation.getArgument(1)),
                        Collections.<SystemMemoryResponse>emptyList())));
    when(client.create(anyString(), anyString(), any(SystemMemoryRequest.class)))
        .thenAnswer(
            invocation -> {
              String owner = owner(invocation.getArgument(0), invocation.getArgument(1));
              SystemMemoryRequest request = invocation.getArgument(2);
              SystemMemoryResponse response = new SystemMemoryResponse();
              response.setId("mem_test" + sequence.incrementAndGet());
              response.setType(request.getType());
              response.setContent(request.getContent());
              response.setSource(request.getSource());
              response.setEnabled(request.getEnabled());
              stored
                  .computeIfAbsent(owner, ignored -> new ArrayList<SystemMemoryResponse>())
                  .add(0, response);
              return response;
            });
    when(client.search(anyString(), anyString(), anyString(), anyInt()))
        .thenAnswer(
            invocation ->
                new ArrayList<SystemMemoryResponse>(
                    stored.getOrDefault(
                        owner(invocation.getArgument(0), invocation.getArgument(1)),
                        Collections.<SystemMemoryResponse>emptyList())));
    return client;
  }

  private String owner(Object tenantId, Object userId) {
    return String.valueOf(tenantId) + "\u0000" + String.valueOf(userId);
  }

  private String key(String scope, String settingKey) {
    return scope + "\u0000" + settingKey;
  }
}
