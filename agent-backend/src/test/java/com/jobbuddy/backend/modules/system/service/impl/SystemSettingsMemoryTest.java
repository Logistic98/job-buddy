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

/**
 * 验证 SystemSettingsMemory 的核心行为、异常路径与边界条件。
 */
class SystemSettingsMemoryTest {

  /**
   * 验证 SystemSettingsMemory 中记忆的核心业务契约。
   */
  @Test
  void autoMemoryShouldPersistStableSignalsByDefault() {
    AgentMemoryClient client = statefulClient();
    SystemSettingsServiceImpl service =
        newService(statefulMapper(new LinkedHashMap<String, String>()), client);

    service.writeLocalMemory("tenant-a", "user-a", "我希望做后端", "chat");

    assertEquals(1, service.listMemories("tenant-a", "user-a").size());
  }

  /**
   * 验证用户显式关闭自动保存后不写入长期记忆。
   */
  @Test
  void autoMemoryShouldRespectExplicitlyDisabledPolicy() {
    Map<String, String> stored = new LinkedHashMap<String, String>();
    stored.put(
        key("global", "settings"),
        "{\"memory\":{\"enabled\":true,\"autoSaveChat\":false,"
            + "\"autoUseMemory\":true,\"maxItems\":200,\"items\":[]}}");
    AgentMemoryClient client = statefulClient();
    SystemSettingsServiceImpl service = newService(statefulMapper(stored), client);

    service.writeLocalMemory("tenant-a", "user-a", "我希望做后端", "chat");

    verify(client, never()).create(anyString(), anyString(), any(SystemMemoryRequest.class));
  }

  /**
   * 验证 SystemSettingsMemory 中记忆的去重与幂等边界。
   */
  @Test
  void autoMemoryShouldPersistStableSignalsAndDedupeInAgentMemory() {
    AgentMemoryClient client = statefulClient();
    SystemSettingsServiceImpl service = newService(statefulMapper(memoryEnabledState()), client);

    service.writeLocalMemory("tenant-a", "user-a", "我希望做后端", "chat");
    service.writeLocalMemory("tenant-a", "user-a", "排除外包岗位", "chat");
    service.writeLocalMemory("tenant-a", "user-a", "我希望做后端", "chat");

    List<SystemMemoryResponse> items = service.listMemories("tenant-a", "user-a");
    assertEquals(2, items.size());
    assertEquals("排除外包岗位", items.get(0).getContent());
    assertEquals("我希望做后端", items.get(1).getContent());
  }

  /**
   * 验证 SystemSettingsMemory 中记忆的输入校验与拒绝边界。
   */
  @Test
  void autoMemoryShouldRejectTinyContent() {
    AgentMemoryClient client = statefulClient();
    SystemSettingsServiceImpl service = newService(statefulMapper(memoryEnabledState()), client);

    service.writeLocalMemory("tenant-a", "user-a", "喜欢", "chat");

    assertTrue(service.listMemories("tenant-a", "user-a").isEmpty());
  }

  /**
   * 验证 SystemSettingsMemory 中用户的权限与租户隔离边界。
   */
  @Test
  void memoriesMustBeIsolatedAcrossTenantAndUserMatrix() {
    AgentMemoryClient client = statefulClient();
    SystemSettingsServiceImpl service = newService(statefulMapper(memoryEnabledState()), client);
    SystemMemoryRequest item = new SystemMemoryRequest();
    item.setContent("只属于 tenant-a/user-a");

    service.addMemory("tenant-a", "user-a", item);

    assertEquals(1, service.listMemories("tenant-a", "user-a").size());
    assertTrue(service.listMemories("tenant-a", "user-b").isEmpty());
    assertTrue(service.listMemories("tenant-b", "user-a").isEmpty());
  }

  /**
   * 验证 SystemSettingsMemory 的去重与幂等边界。
   */
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

  /**
   * 验证 SystemSettingsMemory 的失败恢复、超时与降级边界。
   */
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

  /**
   * 验证新建服务。
   *
   * @param mapper 数据映射
   * @param client 客户端
   * @return 服务
   */
  private SystemSettingsServiceImpl newService(
      SystemSettingsMapper mapper, AgentMemoryClient client) {
    AgentServiceProperties properties = new AgentServiceProperties();
    properties.setMemoryUrl("http://127.0.0.1:8030");
    return new SystemSettingsServiceImpl(properties, new JobBuddyProperties(), mapper, client);
  }

  /**
   * 验证记忆启用状态状态。
   *
   * @return memoryEnabled 状态
   */
  private Map<String, String> memoryEnabledState() {
    Map<String, String> stored = new LinkedHashMap<String, String>();
    stored.put(
        key("global", "settings"),
        "{\"memory\":{\"enabled\":true,\"autoSaveChat\":true,"
            + "\"autoUseMemory\":true,\"maxItems\":200,\"items\":[]}}");
    return stored;
  }

  /**
   * 构造可记录状态的数据访问替身。
   *
   * @param stored 已存储数据
   * @return 测试会话状态 fulMapper
   */
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

  /**
   * 构造可记录状态的记忆客户端替身。
   *
   * @return 测试会话状态 ful 客户端
   */
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

  /**
   * 验证属主。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 测试资源所有者
   */
  private String owner(Object tenantId, Object userId) {
    return String.valueOf(tenantId) + "\u0000" + String.valueOf(userId);
  }

  /**
   * 验证键。
   *
   * @param scope 作用域
   * @param settingKey 设置键
   * @return 业务键
   */
  private String key(String scope, String settingKey) {
    return scope + "\u0000" + settingKey;
  }
}
