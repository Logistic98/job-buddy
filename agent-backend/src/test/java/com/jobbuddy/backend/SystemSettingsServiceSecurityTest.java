package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.system.client.AgentMemoryClient;
import com.jobbuddy.backend.modules.system.dto.request.SystemSettingsRequest;
import com.jobbuddy.backend.modules.system.mapper.SystemSettingsMapper;
import com.jobbuddy.backend.modules.system.service.impl.SystemSettingsServiceImpl;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证 SystemSettingsService 的核心行为、异常路径与边界条件。
 */
class SystemSettingsServiceSecurityTest {
  private static final JsonCodec JSON = new JsonCodec();
  private final String originalUserHome = System.getProperty("user.home");

  @TempDir Path tempDir;

  /**
   * 验证 Boss 检索部署默认值兼顾候选覆盖与有界分页。
   */
  @Test
  void bossSearchDefaultsUseModerateBatchAndBoundedDepth() {
    JobBuddyProperties properties = new JobBuddyProperties();

    assertEquals(3, properties.getBossSearchMaxPages());
    assertEquals(15, properties.getBossSearchMaxPageDepth());
  }

  /**
   * 清理测试创建的资源与上下文。
   */
  @AfterEach
  void tearDown() {
    if (originalUserHome == null) {
      System.clearProperty("user.home");
    } else {
      System.setProperty("user.home", originalUserHome);
    }
  }

  /**
   * 验证启动环境地址覆盖历史持久化地址，且保存时不再写入服务地址。
   */
  @Test
  @SuppressWarnings("unchecked")
  void environmentServiceUrlsOverrideAndRemovePersistedUrls() {
    AtomicReference<String> savedJson =
        new AtomicReference<String>(JSON.toJson(settingsWithRuntimeUrl("http://127.0.0.1:8010")));
    SystemSettingsMapper mapper = mock(SystemSettingsMapper.class);
    when(mapper.listBlacklistItems()).thenReturn(Collections.<Map<String, Object>>emptyList());
    when(mapper.findSettingJson("global", "settings")).thenAnswer(invocation -> savedJson.get());
    when(mapper.upsertSetting(anyString(), anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              savedJson.set(invocation.getArgument(2, String.class));
              return Integer.valueOf(1);
            });
    AgentServiceProperties agentProperties = new AgentServiceProperties();
    agentProperties.setRuntimeUrl("http://agent-runtime:8010");
    SystemSettingsServiceImpl service =
        new SystemSettingsServiceImpl(
            agentProperties, new JobBuddyProperties(), mapper, mock(AgentMemoryClient.class));

    service.loadPersistedRuntimeSettings();
    Map<String, Object> loaded = JSON.toMap(service.getSettings());
    Map<String, Object> services = (Map<String, Object>) loaded.get("services");
    assertEquals("http://agent-runtime:8010", services.get("runtimeUrl"));
    Map<String, Object> cleanedAtStartup = JSON.toMap(savedJson.get());
    Map<String, Object> cleanedServices =
        (Map<String, Object>) cleanedAtStartup.getOrDefault("services", Collections.emptyMap());
    assertFalse(cleanedServices.containsKey("runtimeUrl"));

    Map<String, Object> submitted = settingsWithRuntimeUrl("http://192.0.2.57:8010");
    assertDoesNotThrow(
        () -> service.saveSettings(JSON.convert(submitted, SystemSettingsRequest.class)));

    Map<String, Object> persisted = JSON.toMap(savedJson.get());
    Map<String, Object> persistedServices =
        (Map<String, Object>) persisted.getOrDefault("services", Collections.emptyMap());
    assertFalse(persistedServices.containsKey("runtimeUrl"));
  }

  /**
   * 验证 SystemSettingsService 的检索、筛选与排序规则。
   */
  @Test
  void companyBlacklistOnlyMatchesCompanyFields() {
    SystemSettingsServiceImpl service =
        newService(Arrays.asList(blacklistItem("company", "示例科技", true)));

    assertTrue(service.isBlacklistedJob(job("Go 开发", "示例科技（杭州）有限公司", "负责平台研发")));
    assertFalse(service.isBlacklistedJob(job("Java 开发", "其他公司", "服务示例科技客户")));
  }

  /**
   * 验证 SystemSettingsService 中岗位的检索、筛选与排序规则。
   */
  @Test
  void keywordBlacklistOnlyMatchesJobContentFields() {
    SystemSettingsServiceImpl service =
        newService(Arrays.asList(blacklistItem("keyword", "驻场", true)));

    assertTrue(service.isBlacklistedJob(job("Java 驻场开发", "其他公司", "负责平台研发")));
    assertTrue(service.isBlacklistedJob(job("Java 开发", "其他公司", "需要长期驻场")));
    assertFalse(service.isBlacklistedJob(job("Java 开发", "驻场科技", "负责平台研发")));
  }

  /**
   * 验证 SystemSettingsService 的身份认证与会话边界。
   */
  @Test
  void shortAsciiKeywordUsesTokenBoundaries() {
    SystemSettingsServiceImpl service =
        newService(Arrays.asList(blacklistItem("keyword", "OD", true)));

    assertTrue(service.isBlacklistedJob(job("Java OD 开发", "其他公司", "负责平台研发")));
    assertTrue(service.isBlacklistedJob(job("Java 开发", "其他公司", "华为OD岗位")));
    assertFalse(service.isBlacklistedJob(job("Java 开发", "其他公司", "负责大模型 model 开发")));
    assertFalse(service.isBlacklistedJob(job("Java 开发", "其他公司", "负责 product 研发")));
  }

  /**
   * 验证 SystemSettingsService 的检索、筛选与排序规则。
   */
  @Test
  void disabledAndUnknownBlacklistItemsDoNotMatch() {
    SystemSettingsServiceImpl service =
        newService(
            Arrays.asList(
                blacklistItem("company", "示例科技", false), blacklistItem("other", "长期驻场", true)));

    assertFalse(service.isBlacklistedJob(job("Java 开发", "示例科技", "需要长期驻场")));
    assertFalse(service.isBlacklistedJob(Collections.<String, Object>emptyMap()));
  }

  /**
   * 验证批量黑名单过滤仅加载一次设置。
   */
  @Test
  void batchBlacklistFilterLoadsSettingsOnlyOnce() {
    SystemSettingsMapper mapper = mock(SystemSettingsMapper.class);
    when(mapper.findSettingJson("global", "settings")).thenReturn(null);
    when(mapper.listBlacklistItems())
        .thenReturn(Arrays.asList(blacklistItem("company", "示例科技", true)));
    SystemSettingsServiceImpl service =
        new SystemSettingsServiceImpl(
            new AgentServiceProperties(),
            new JobBuddyProperties(),
            mapper,
            mock(AgentMemoryClient.class));

    List<Map<String, Object>> result =
        service.filterBlacklistedJobs(
            Arrays.asList(
                job("Java 开发", "示例科技", "负责平台研发"), job("大模型应用开发", "其他公司", "负责 RAG 与 Agent 开发")));

    assertEquals(1, result.size());
    assertEquals("其他公司", result.get(0).get("brandName"));
    verify(mapper, times(1)).findSettingJson("global", "settings");
    verify(mapper, times(1)).listBlacklistItems();
  }

  /**
   * 验证 SystemSettingsService 的持久化与状态变更规则。
   */
  @Test
  @SuppressWarnings("unchecked")
  void restoreWorkspaceDefaultsRemovesOnlyWorkspaceOverrides() {
    AtomicReference<String> savedJson = new AtomicReference<String>();
    Map<String, Object> persisted = new LinkedHashMap<String, Object>();
    persisted.put("workspace", Collections.singletonMap("maxJobsPerRecommend", 29));
    persisted.put("memory", Collections.singletonMap("maxItems", 321));
    savedJson.set(JSON.toJson(persisted));

    SystemSettingsMapper mapper = mock(SystemSettingsMapper.class);
    when(mapper.listBlacklistItems()).thenReturn(Collections.<Map<String, Object>>emptyList());
    when(mapper.findSettingJson("global", "settings")).thenAnswer(invocation -> savedJson.get());
    when(mapper.upsertSetting(anyString(), anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              savedJson.set(invocation.getArgument(2, String.class));
              return Integer.valueOf(1);
            });
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setMaxJobsPerRecommend(17);
    SystemSettingsServiceImpl service =
        new SystemSettingsServiceImpl(
            new AgentServiceProperties(), properties, mapper, mock(AgentMemoryClient.class));

    Map<String, Object> loaded = JSON.toMap(service.getSettings());
    assertEquals(29, ((Map<String, Object>) loaded.get("workspace")).get("maxJobsPerRecommend"));

    Map<String, Object> restored = JSON.toMap(service.restoreWorkspaceDefaults());
    assertEquals(17, ((Map<String, Object>) restored.get("workspace")).get("maxJobsPerRecommend"));
    assertEquals(321, ((Map<String, Object>) restored.get("memory")).get("maxItems"));
    assertFalse(JSON.toMap(savedJson.get()).containsKey("workspace"));
  }

  /**
   * 验证 SystemSettingsService 中运行时的持久化与状态变更规则。
   */
  @Test
  void startupLoadAppliesPersistedRuntimeSettingsBeforeSettingsPageIsOpened() {
    Map<String, Object> workspace = new LinkedHashMap<String, Object>();
    workspace.put("bossSearchCacheTtlMinutes", 45);
    workspace.put("runtimeMaxTurns", 9);
    workspace.put("runtimeMaxToolCalls", 14);
    workspace.put("runtimeMaxFailures", 4);
    Map<String, Object> persisted = new LinkedHashMap<String, Object>();
    persisted.put("workspace", workspace);

    SystemSettingsMapper mapper = mock(SystemSettingsMapper.class);
    when(mapper.findSettingJson("global", "settings")).thenReturn(JSON.toJson(persisted));
    when(mapper.listBlacklistItems()).thenReturn(Collections.<Map<String, Object>>emptyList());
    JobBuddyProperties properties = new JobBuddyProperties();
    SystemSettingsServiceImpl service =
        new SystemSettingsServiceImpl(
            new AgentServiceProperties(), properties, mapper, mock(AgentMemoryClient.class));

    service.loadPersistedRuntimeSettings();

    assertEquals(45, properties.getBossSearchCacheTtlMinutes());
    assertEquals(9, properties.getRuntimeMaxTurns());
    assertEquals(14, properties.getRuntimeMaxToolCalls());
    assertEquals(4, properties.getRuntimeMaxFailures());
  }

  /**
   * 验证 SystemSettingsService 中设置的失败恢复、超时与降级边界。
   */
  @Test
  void startupLoadKeepsDeploymentDefaultsWhenSettingsTableIsUnavailable() {
    SystemSettingsMapper mapper = mock(SystemSettingsMapper.class);
    when(mapper.findSettingJson("global", "settings"))
        .thenThrow(new RuntimeException("platform_setting unavailable"));
    JobBuddyProperties properties = new JobBuddyProperties();
    SystemSettingsServiceImpl service =
        new SystemSettingsServiceImpl(
            new AgentServiceProperties(), properties, mapper, mock(AgentMemoryClient.class));

    assertDoesNotThrow(service::loadPersistedRuntimeSettings);
    assertEquals(12, properties.getRuntimeMaxTurns());
    assertEquals(20, properties.getRuntimeMaxToolCalls());
    assertEquals(3, properties.getRuntimeMaxFailures());
  }

  /**
   * 验证 SystemSettingsService 中运行时的输入校验与拒绝边界。
   */
  @Test
  @SuppressWarnings("unchecked")
  void saveSettingsNormalizesBusinessRuntimeParametersAndDropsUnsupportedFields() {
    JobBuddyProperties properties = new JobBuddyProperties();
    SystemSettingsServiceImpl service =
        newService(Collections.<Map<String, Object>>emptyList(), properties);
    Map<String, Object> workspace = new LinkedHashMap<String, Object>();
    workspace.put("name", "unsupported-name");
    workspace.put("defaultUserId", "unsupported-user");
    workspace.put("resumeRuntimeWorkspace", "/tmp/unsupported");
    workspace.put("maxJobsPerRecommend", 99);
    workspace.put("recommendOverfetchFactor", 0);
    workspace.put("maxJobsPerScoring", 500);
    workspace.put("minimumRecommendedMatchScore", 500);
    workspace.put("bossSearchMaxPages", 0);
    workspace.put("bossSearchMaxPageDepth", 99);
    workspace.put("bossSearchCacheTtlMinutes", 0);
    workspace.put("bossSearchCooldownMinutesOnRisk", 9999);
    workspace.put("runtimeMaxTurns", 0);
    workspace.put("runtimeMaxToolCalls", 99);
    workspace.put("runtimeMaxFailures", 0);
    workspace.put("maxResumeBytes", 1);
    workspace.put("resumeWriterVersionLimit", 999);
    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("workspace", workspace);

    Map<String, Object> saved =
        JSON.toMap(service.saveSettings(JSON.convert(payload, SystemSettingsRequest.class)));
    Map<String, Object> normalized = (Map<String, Object>) saved.get("workspace");

    assertEquals(30, normalized.get("maxJobsPerRecommend"));
    assertEquals(1, normalized.get("recommendOverfetchFactor"));
    assertFalse(normalized.containsKey("maxJobsPerScoring"));
    assertEquals(100, normalized.get("minimumRecommendedMatchScore"));
    assertEquals(1, normalized.get("bossSearchMaxPages"));
    assertEquals(30, normalized.get("bossSearchMaxPageDepth"));
    assertEquals(1, normalized.get("bossSearchCacheTtlMinutes"));
    assertEquals(1440, normalized.get("bossSearchCooldownMinutesOnRisk"));
    assertEquals(1, normalized.get("runtimeMaxTurns"));
    assertEquals(30, normalized.get("runtimeMaxToolCalls"));
    assertEquals(1, normalized.get("runtimeMaxFailures"));
    assertEquals(1024 * 1024, normalized.get("maxResumeBytes"));
    assertEquals(100, normalized.get("resumeWriterVersionLimit"));
    assertFalse(normalized.containsKey("name"));
    assertFalse(normalized.containsKey("defaultUserId"));
    assertFalse(normalized.containsKey("resumeRuntimeWorkspace"));

    assertEquals(30, properties.getMaxJobsPerRecommend());
    assertEquals(1, properties.getRecommendOverfetchFactor());
    assertEquals(30, properties.getMaxJobsPerScoring());
    assertEquals(100, properties.getMinimumRecommendedMatchScore());
    assertEquals(1, properties.getBossSearchMaxPages());
    assertEquals(30, properties.getBossSearchMaxPageDepth());
    assertEquals(1, properties.getBossSearchCacheTtlMinutes());
    assertEquals(1440, properties.getBossSearchCooldownMinutesOnRisk());
    assertEquals(1, properties.getRuntimeMaxTurns());
    assertEquals(30, properties.getRuntimeMaxToolCalls());
    assertEquals(1, properties.getRuntimeMaxFailures());
    assertEquals(1024 * 1024, properties.getMaxResumeBytes());
    assertEquals(100, properties.getResumeWriterVersionLimit());
  }

  /**
   * 验证新建服务。
   *
   * @return 服务
   */
  private SystemSettingsServiceImpl newService() {
    return newService(Collections.<Map<String, Object>>emptyList());
  }

  /**
   * 验证新建服务。
   *
   * @param blacklistItems 黑名单数据项列表
   * @return 服务
   */
  private SystemSettingsServiceImpl newService(List<Map<String, Object>> blacklistItems) {
    return newService(blacklistItems, new JobBuddyProperties());
  }

  /**
   * 验证新建服务。
   *
   * @param blacklistItems 黑名单数据项列表
   * @param properties 配置属性
   * @return 服务
   */
  private SystemSettingsServiceImpl newService(
      List<Map<String, Object>> blacklistItems, JobBuddyProperties properties) {
    AtomicReference<String> savedJson = new AtomicReference<String>();
    SystemSettingsMapper mapper = mock(SystemSettingsMapper.class);
    when(mapper.listBlacklistItems()).thenReturn(blacklistItems);
    when(mapper.findSettingJson("global", "settings")).thenAnswer(invocation -> savedJson.get());
    when(mapper.upsertSetting(anyString(), anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              savedJson.set(invocation.getArgument(2, String.class));
              return Integer.valueOf(1);
            });
    AgentServiceProperties agentProperties = new AgentServiceProperties();
    agentProperties.setRuntimeUrl("http://127.0.0.1:8010");
    return new SystemSettingsServiceImpl(
        agentProperties, properties, mapper, mock(AgentMemoryClient.class));
  }

  /**
   * 验证黑名单数据项。
   *
   * @param type 类型
   * @param name 名称
   * @param enabled 启用状态
   * @return 测试黑名单项
   */
  private Map<String, Object> blacklistItem(String type, String name, boolean enabled) {
    Map<String, Object> item = new LinkedHashMap<String, Object>();
    item.put("id", type + "_" + name);
    item.put("type", type);
    item.put("name", name);
    item.put("enabled", Boolean.valueOf(enabled));
    item.put("source", "system");
    return item;
  }

  /**
   * 验证岗位。
   *
   * @param title 标题
   * @param company 公司
   * @param description 说明文本
   * @return 测试岗位
   */
  private Map<String, Object> job(String title, String company, String description) {
    Map<String, Object> job = new LinkedHashMap<String, Object>();
    job.put("jobName", title);
    job.put("brandName", company);
    job.put("jobDescription", description);
    return job;
  }

  /**
   * 构造包含运行时地址的设置。
   *
   * @param url Runtime 服务地址
   * @return 包含 Runtime 地址的设置
   */
  private Map<String, Object> settingsWithRuntimeUrl(String url) {
    Map<String, Object> services = new LinkedHashMap<String, Object>();
    services.put("runtimeUrl", url);
    Map<String, Object> root = new LinkedHashMap<String, Object>();
    root.put("services", services);
    return root;
  }
}
