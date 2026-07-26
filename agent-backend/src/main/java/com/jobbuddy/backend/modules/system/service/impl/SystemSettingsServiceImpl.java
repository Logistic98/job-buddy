package com.jobbuddy.backend.modules.system.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.system.client.AgentMemoryClient;
import com.jobbuddy.backend.modules.system.dto.request.SystemMemoryRequest;
import com.jobbuddy.backend.modules.system.dto.request.SystemSettingsRequest;
import com.jobbuddy.backend.modules.system.dto.response.ServiceStatusesResponse;
import com.jobbuddy.backend.modules.system.dto.response.SystemMemoryResponse;
import com.jobbuddy.backend.modules.system.dto.response.SystemSettingsResponse;
import com.jobbuddy.backend.modules.system.mapper.SystemSettingsMapper;
import com.jobbuddy.backend.modules.system.service.SystemSettingsService;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 持久化平台设置、应用校验后的运行限制并代理用户记忆。
 *
 * <p>部署配置是降级基线；设置表瞬时故障不阻断启动，显式记忆写入失败时也不落本地副本。
 */
@Service
public class SystemSettingsServiceImpl implements SystemSettingsService {
  private static final Logger LOG = LoggerFactory.getLogger(SystemSettingsServiceImpl.class);
  private static final String SETTINGS_SCOPE = "global";
  private static final String SETTINGS_KEY = "settings";
  private static final String USER_MEMORY_KEY = "memory";
  private static final String USER_MEMORY_SCOPE_PREFIX = "user-memory:";
  private static final int MIN_JOBS_PER_RECOMMEND = 1;
  private static final int MAX_JOBS_PER_RECOMMEND = 30;
  private static final int MIN_RECOMMEND_OVERFETCH_FACTOR = 1;
  private static final int MAX_RECOMMEND_OVERFETCH_FACTOR = 10;
  private static final int MIN_RECOMMENDED_MATCH_SCORE = 0;
  private static final int MAX_RECOMMENDED_MATCH_SCORE = 100;
  private static final int MIN_BOSS_SEARCH_MAX_PAGES = 1;
  private static final int MAX_BOSS_SEARCH_MAX_PAGES = 5;
  private static final int MIN_BOSS_SEARCH_MAX_PAGE_DEPTH = 1;
  private static final int MAX_BOSS_SEARCH_MAX_PAGE_DEPTH = 10;
  private static final int MIN_BOSS_SEARCH_MINUTES = 1;
  private static final int MAX_BOSS_SEARCH_MINUTES = 24 * 60;
  private static final int MIN_RUNTIME_MAX_TURNS = 1;
  private static final int MAX_RUNTIME_MAX_TURNS = 20;
  private static final int MIN_RUNTIME_MAX_TOOL_CALLS = 1;
  private static final int MAX_RUNTIME_MAX_TOOL_CALLS = 30;
  private static final int MIN_RUNTIME_MAX_FAILURES = 1;
  private static final int MAX_RUNTIME_MAX_FAILURES = 10;
  private static final int MIN_RESUME_BYTES = 1024 * 1024;
  private static final int MAX_RESUME_BYTES = 20 * 1024 * 1024;
  private static final int MIN_RESUME_WRITER_VERSION_LIMIT = 5;
  private static final int MAX_RESUME_WRITER_VERSION_LIMIT = 100;
  private static final String[] WORKSPACE_SETTING_KEYS = {
    "maxJobsPerRecommend",
    "recommendOverfetchFactor",
    "minimumRecommendedMatchScore",
    "bossSearchMaxPages",
    "bossSearchMaxPageDepth",
    "bossSearchCacheTtlMinutes",
    "bossSearchCooldownMinutesOnRisk",
    "runtimeMaxTurns",
    "runtimeMaxToolCalls",
    "runtimeMaxFailures",
    "maxResumeBytes",
    "resumeWriterVersionLimit"
  };

  private final AgentServiceProperties agentServiceProperties;
  private final JobBuddyProperties jobBuddyProperties;
  private final Map<String, Object> workspaceDefaultSettings;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final SystemSettingsMapper systemSettingsMapper;
  private final AgentMemoryClient agentMemoryClient;
  private final JsonCodec jsonCodec = new JsonCodec();
  private final ServiceHealthMonitor serviceHealthMonitor;
  private final JobBlacklistPolicy blacklistPolicy;
  private boolean persistedRuntimeSettingsLoaded;

  /**
   * 创建系统设置服务实例。
   *
   * @param agentServiceProperties Agent 服务配置属性
   * @param jobBuddyProperties JobBuddy 配置属性
   * @param systemSettingsMapper 系统设置数据访问组件
   * @param agentMemoryClient Agent 记忆客户端
   */
  public SystemSettingsServiceImpl(
      AgentServiceProperties agentServiceProperties,
      JobBuddyProperties jobBuddyProperties,
      SystemSettingsMapper systemSettingsMapper,
      AgentMemoryClient agentMemoryClient) {
    this.agentServiceProperties = agentServiceProperties;
    this.jobBuddyProperties = jobBuddyProperties;
    this.workspaceDefaultSettings = workspaceSettingsFromProperties();
    this.systemSettingsMapper = systemSettingsMapper;
    this.agentMemoryClient = agentMemoryClient;
    this.serviceHealthMonitor =
        new ServiceHealthMonitor(agentServiceProperties, jobBuddyProperties);
    this.blacklistPolicy = new JobBlacklistPolicy(systemSettingsMapper);
    this.objectMapper.findAndRegisterModules();
    this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  /**
   * 在接收业务请求前加载持久化运行参数，避免首个请求短暂使用部署默认值。
   */
  @PostConstruct
  public synchronized void loadPersistedRuntimeSettings() {
    try {
      ensurePersistedRuntimeSettingsLoaded();
    } catch (RuntimeException error) {
      // 配置表暂不可用时保留部署默认值；persistedRuntimeSettingsLoaded 仍为 false，
      // 后续健康轮询会继续加载，不能让旁路设置故障阻断整个 Backend 启动。
      LOG.warn("启动时加载平台运行参数失败，将保留部署默认值并重试: {}", error.getMessage());
    }
  }

  /**
   * 获取合并持久化配置后的平台运行设置。
   *
   * @return 设置
   */
  public synchronized SystemSettingsResponse getSettings() {
    return jsonCodec.convert(getSettingsMap(), SystemSettingsResponse.class);
  }

  /**
   * 获取设置映射。
   *
   * @return 设置映射
   */
  private synchronized Map<String, Object> getSettingsMap() {
    Map<String, Object> settings = defaultSettings();
    Map<String, Object> saved = readSavedSettings();
    deepMerge(settings, saved);
    // 全局配置只允许保存记忆策略；个人记忆正文必须按租户和用户独立存储。
    enforceGlobalMemoryPolicy(settings);
    retainBusinessRuntimeSettings(settings);
    applyBlacklistItems(settings, saved);
    applyRuntimeSettings(settings);
    persistedRuntimeSettingsLoaded = true;
    settings.put("runtime", runtimeSettings());
    settings.put("serviceStatuses", serviceStatuses());
    settings.put("settingsPath", "PostgreSQL: platform_setting/global/settings");
    return settings;
  }

  /**
   * 保存配置并刷新运行参数与服务状态。
   *
   * @param request 请求对象
   * @return 保存后的设置
   */
  public synchronized SystemSettingsResponse saveSettings(SystemSettingsRequest request) {
    Map<String, Object> payload = jsonCodec.toMap(request);
    Map<String, Object> current = getSettingsWithoutRuntime();
    deepMerge(current, sanitize(payload));
    current.put("updatedAt", Instant.now().toString());
    applyRuntimeSettings(current);
    writeSettings(current);
    refreshServiceStatuses();
    return getSettings();
  }

  /**
   * 恢复工作区默认值。
   *
   * @return 恢复后的工作区默认设置
   */
  public synchronized SystemSettingsResponse restoreWorkspaceDefaults() {
    Map<String, Object> saved = readSavedSettings();
    saved.remove("workspace");
    saved.put("updatedAt", Instant.now().toString());
    writeSettings(saved);
    return getSettings();
  }

  /**
   * 查询记忆列表。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 记忆列表
   */
  public synchronized List<SystemMemoryResponse> listMemories(String tenantId, String userId) {
    migrateLegacyMemories(tenantId, userId);
    return agentMemoryClient.list(tenantId, userId);
  }

  /**
   * 新增记忆。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param request 请求对象
   * @return 新增后的记忆
   */
  public synchronized SystemMemoryResponse addMemory(
      String tenantId, String userId, SystemMemoryRequest request) {
    migrateLegacyMemories(tenantId, userId);
    SystemMemoryRequest normalized = normalizeMemoryRequest(request, "manual");
    List<SystemMemoryResponse> items = agentMemoryClient.list(tenantId, userId);
    SystemMemoryResponse existing = findSameMemory(items, normalized);
    if (existing != null) {
      return existing;
    }
    SystemMemoryResponse created = agentMemoryClient.create(tenantId, userId, normalized);
    trimMemories(tenantId, userId, items, created);
    return created;
  }

  /**
   * 写入本地数据记忆。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param content 内容
   * @param source 源数据
   */
  public synchronized void writeLocalMemory(
      String tenantId, String userId, String content, String source) {
    requireMemoryOwner(tenantId, userId);
    if (content == null || content.trim().length() < 2) return;
    if (!shouldAutoWriteMemory(content)) return;
    Map<String, Object> policy = memoryPolicy();
    if (!booleanValue(policy.get("enabled"), true)
        || !booleanValue(policy.get("autoSaveChat"), true)) return;
    migrateLegacyMemories(tenantId, userId);
    SystemMemoryRequest request = new SystemMemoryRequest();
    request.setContent(content);
    request.setSource(source);
    request.setEnabled(Boolean.TRUE);
    addMemory(tenantId, userId, request);
  }

  /**
   * 删除记忆。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param memoryId 记忆标识
   */
  public synchronized void deleteMemory(String tenantId, String userId, String memoryId) {
    migrateLegacyMemories(tenantId, userId);
    agentMemoryClient.delete(tenantId, userId, memoryId);
  }

  /**
   * 清理记忆。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 记忆清理结果
   */
  public synchronized int clearMemories(String tenantId, String userId) {
    migrateLegacyMemories(tenantId, userId);
    return agentMemoryClient.clear(tenantId, userId);
  }

  /**
   * 检索本地数据记忆。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param query 查询条件
   * @param limit 数量上限
   * @return 本地记忆搜索结果
   */
  public synchronized List<SystemMemoryResponse> searchLocalMemories(
      String tenantId, String userId, String query, int limit) {
    Map<String, Object> policy = memoryPolicy();
    if (!booleanValue(policy.get("enabled"), true)
        || !booleanValue(policy.get("autoUseMemory"), true))
      return new ArrayList<SystemMemoryResponse>();
    String normalizedQuery = normalizeMemoryText(query);
    if (normalizedQuery.length() < 4) return new ArrayList<SystemMemoryResponse>();
    migrateLegacyMemories(tenantId, userId);
    return agentMemoryClient.search(tenantId, userId, query, Math.max(1, Math.min(limit, 2)));
  }

  /**
   * 获取默认设置。
   *
   * @return 默认设置
   */
  private Map<String, Object> defaultSettings() {
    Map<String, Object> root = new LinkedHashMap<String, Object>();
    root.put("workspace", workspaceDefaults());
    root.put("services", serviceDefaults());
    root.put("memory", memoryDefaults());
    root.put("blacklist", blacklistDefaults());
    root.put("updatedAt", null);
    return root;
  }

  /**
   * 获取工作区默认值。
   *
   * @return 工作区默认值
   */
  private Map<String, Object> workspaceDefaults() {
    return new LinkedHashMap<String, Object>(workspaceDefaultSettings);
  }

  /**
   * 从配置属性构建工作区设置。
   *
   * @return 配置生成的工作区设置
   */
  private Map<String, Object> workspaceSettingsFromProperties() {
    Map<String, Object> data = new LinkedHashMap<String, Object>();
    data.put("maxJobsPerRecommend", jobBuddyProperties.getMaxJobsPerRecommend());
    data.put("recommendOverfetchFactor", jobBuddyProperties.getRecommendOverfetchFactor());
    data.put("minimumRecommendedMatchScore", jobBuddyProperties.getMinimumRecommendedMatchScore());
    data.put("bossSearchMaxPages", jobBuddyProperties.getBossSearchMaxPages());
    data.put("bossSearchMaxPageDepth", jobBuddyProperties.getBossSearchMaxPageDepth());
    data.put("bossSearchCacheTtlMinutes", jobBuddyProperties.getBossSearchCacheTtlMinutes());
    data.put(
        "bossSearchCooldownMinutesOnRisk", jobBuddyProperties.getBossSearchCooldownMinutesOnRisk());
    data.put("runtimeMaxTurns", jobBuddyProperties.getRuntimeMaxTurns());
    data.put("runtimeMaxToolCalls", jobBuddyProperties.getRuntimeMaxToolCalls());
    data.put("runtimeMaxFailures", jobBuddyProperties.getRuntimeMaxFailures());
    data.put("maxResumeBytes", jobBuddyProperties.getMaxResumeBytes());
    data.put("resumeWriterVersionLimit", jobBuddyProperties.getResumeWriterVersionLimit());
    return data;
  }

  /**
   * 保留业务运行时设置。
   *
   * @param settings 设置
   */
  @SuppressWarnings("unchecked")
  private void retainBusinessRuntimeSettings(Map<String, Object> settings) {
    Map<String, Object> defaults = workspaceDefaults();
    Object workspaceValue = settings.get("workspace");
    if (workspaceValue instanceof Map) {
      Map<String, Object> workspace = (Map<String, Object>) workspaceValue;
      for (String key : WORKSPACE_SETTING_KEYS) {
        if (workspace.containsKey(key)) defaults.put(key, workspace.get(key));
      }
    }
    settings.put("workspace", defaults);
  }

  /**
   * 获取记忆默认值。
   *
   * @return 记忆默认值
   */
  private Map<String, Object> memoryDefaults() {
    Map<String, Object> data = new LinkedHashMap<String, Object>();
    data.put("enabled", true);
    data.put("autoSaveChat", true);
    data.put("autoUseMemory", true);
    data.put("maxItems", 200);
    data.put("items", new ArrayList<Map<String, Object>>());
    return data;
  }

  /**
   * 获取黑名单默认值。
   *
   * @return 黑名单默认值
   */
  private Map<String, Object> blacklistDefaults() {
    return blacklistPolicy.defaults();
  }

  /**
   * 查询黑名单数据项列表。
   *
   * @return 黑名单数据项列表
   */
  @SuppressWarnings("unchecked")
  public synchronized List<SystemSettingsResponse.Item> listBlacklistItems() {
    return jsonCodec.convertList(listBlacklistItemsMap(), SystemSettingsResponse.Item.class);
  }

  /**
   * 查询黑名单数据项映射列表。
   *
   * @return 黑名单数据项映射列表
   */
  @SuppressWarnings("unchecked")
  private synchronized List<Map<String, Object>> listBlacklistItemsMap() {
    return blacklistPolicy.listItems(getSettingsMap());
  }

  /**
   * 应用黑名单数据项。
   *
   * @param settings 设置
   * @param savedSettings 已保存设置
   */
  @SuppressWarnings("unchecked")
  private void applyBlacklistItems(
      Map<String, Object> settings, Map<String, Object> savedSettings) {
    blacklistPolicy.applyItems(settings, savedSettings);
  }

  /**
   * 判断岗位是否命中黑名单。
   *
   * @param job 岗位
   * @return 岗位是否命中黑名单
   */
  public synchronized boolean isBlacklistedJob(Map<String, Object> job) {
    return blacklistPolicy.isBlacklisted(job, readSavedSettings());
  }

  /**
   * 筛除命中黑名单的岗位。
   *
   * @param jobs 岗位列表
   * @return 过滤后的岗位列表
   */
  public synchronized List<Map<String, Object>> filterBlacklistedJobs(
      List<Map<String, Object>> jobs) {
    return blacklistPolicy.filter(jobs, readSavedSettings());
  }

  /**
   * 获取服务默认值。
   *
   * @return 服务默认值
   */
  private Map<String, Object> serviceDefaults() {
    return serviceHealthMonitor.serviceDefaults();
  }

  /**
   * 获取运行时设置。
   *
   * @return 运行时设置
   */
  private Map<String, Object> runtimeSettings() {
    return serviceHealthMonitor.runtimeSettings();
  }

  /**
   * 获取服务状态。
   *
   * @return 服务状态
   */
  private synchronized Map<String, Object> serviceStatuses() {
    return serviceHealthMonitor.statuses();
  }

  /**
   * 按调度周期刷新服务健康状态。
   */
  @Scheduled(
      fixedDelayString = "${job-buddy.service-monitor.interval-ms:10000}",
      initialDelayString = "${job-buddy.service-monitor.initial-delay-ms:0}")
  public void monitorServiceHealth() {
    refreshServiceStatuses();
  }

  /**
   * 刷新服务状态。
   *
   * @return 最新服务状态列表
   */
  @Override
  public synchronized ServiceStatusesResponse refreshServiceStatuses() {
    ensurePersistedRuntimeSettingsLoaded();
    return serviceHealthMonitor.refresh();
  }

  /**
   * 确保已加载持久化运行时设置。
   */
  private void ensurePersistedRuntimeSettingsLoaded() {
    if (persistedRuntimeSettingsLoaded) return;
    Map<String, Object> settings = defaultSettings();
    deepMerge(settings, readSavedSettings());
    retainBusinessRuntimeSettings(settings);
    applyRuntimeSettings(settings);
    persistedRuntimeSettingsLoaded = true;
  }

  /**
   * 应用 Runtime 设置。
   *
   * @param settings 设置
   */
  @SuppressWarnings("unchecked")
  private void applyRuntimeSettings(Map<String, Object> settings) {
    Object workspaceValue = settings.get("workspace");
    if (workspaceValue instanceof Map) {
      Map<String, Object> workspace = (Map<String, Object>) workspaceValue;
      applyIntegerSetting(
          workspace,
          "maxJobsPerRecommend",
          MIN_JOBS_PER_RECOMMEND,
          MAX_JOBS_PER_RECOMMEND,
          jobBuddyProperties.getMaxJobsPerRecommend(),
          jobBuddyProperties::setMaxJobsPerRecommend);
      applyIntegerSetting(
          workspace,
          "recommendOverfetchFactor",
          MIN_RECOMMEND_OVERFETCH_FACTOR,
          MAX_RECOMMEND_OVERFETCH_FACTOR,
          jobBuddyProperties.getRecommendOverfetchFactor(),
          jobBuddyProperties::setRecommendOverfetchFactor);
      applyIntegerSetting(
          workspace,
          "minimumRecommendedMatchScore",
          MIN_RECOMMENDED_MATCH_SCORE,
          MAX_RECOMMENDED_MATCH_SCORE,
          jobBuddyProperties.getMinimumRecommendedMatchScore(),
          jobBuddyProperties::setMinimumRecommendedMatchScore);
      applyIntegerSetting(
          workspace,
          "bossSearchMaxPages",
          MIN_BOSS_SEARCH_MAX_PAGES,
          MAX_BOSS_SEARCH_MAX_PAGES,
          jobBuddyProperties.getBossSearchMaxPages(),
          jobBuddyProperties::setBossSearchMaxPages);
      applyIntegerSetting(
          workspace,
          "bossSearchMaxPageDepth",
          MIN_BOSS_SEARCH_MAX_PAGE_DEPTH,
          MAX_BOSS_SEARCH_MAX_PAGE_DEPTH,
          jobBuddyProperties.getBossSearchMaxPageDepth(),
          jobBuddyProperties::setBossSearchMaxPageDepth);
      applyIntegerSetting(
          workspace,
          "bossSearchCacheTtlMinutes",
          MIN_BOSS_SEARCH_MINUTES,
          MAX_BOSS_SEARCH_MINUTES,
          jobBuddyProperties.getBossSearchCacheTtlMinutes(),
          jobBuddyProperties::setBossSearchCacheTtlMinutes);
      applyIntegerSetting(
          workspace,
          "bossSearchCooldownMinutesOnRisk",
          MIN_BOSS_SEARCH_MINUTES,
          MAX_BOSS_SEARCH_MINUTES,
          jobBuddyProperties.getBossSearchCooldownMinutesOnRisk(),
          jobBuddyProperties::setBossSearchCooldownMinutesOnRisk);
      applyIntegerSetting(
          workspace,
          "runtimeMaxTurns",
          MIN_RUNTIME_MAX_TURNS,
          MAX_RUNTIME_MAX_TURNS,
          jobBuddyProperties.getRuntimeMaxTurns(),
          jobBuddyProperties::setRuntimeMaxTurns);
      applyIntegerSetting(
          workspace,
          "runtimeMaxToolCalls",
          MIN_RUNTIME_MAX_TOOL_CALLS,
          MAX_RUNTIME_MAX_TOOL_CALLS,
          jobBuddyProperties.getRuntimeMaxToolCalls(),
          jobBuddyProperties::setRuntimeMaxToolCalls);
      applyIntegerSetting(
          workspace,
          "runtimeMaxFailures",
          MIN_RUNTIME_MAX_FAILURES,
          MAX_RUNTIME_MAX_FAILURES,
          jobBuddyProperties.getRuntimeMaxFailures(),
          jobBuddyProperties::setRuntimeMaxFailures);
      applyIntegerSetting(
          workspace,
          "maxResumeBytes",
          MIN_RESUME_BYTES,
          MAX_RESUME_BYTES,
          jobBuddyProperties.getMaxResumeBytes(),
          jobBuddyProperties::setMaxResumeBytes);
      applyIntegerSetting(
          workspace,
          "resumeWriterVersionLimit",
          MIN_RESUME_WRITER_VERSION_LIMIT,
          MAX_RESUME_WRITER_VERSION_LIMIT,
          jobBuddyProperties.getResumeWriterVersionLimit(),
          jobBuddyProperties::setResumeWriterVersionLimit);
    }
    Object servicesValue = settings.get("services");
    if (servicesValue instanceof Map) {
      Map<String, Object> services = (Map<String, Object>) servicesValue;
      setText(
          services,
          "intentUrl",
          new TextSetter() {
            /**
             * 设置目标值。
             *
             * @param value 输入值
             */
            public void set(String value) {
              agentServiceProperties.setIntentUrl(value);
            }
          });
      setText(
          services,
          "runtimeUrl",
          new TextSetter() {
            /**
             * 设置目标值。
             *
             * @param value 输入值
             */
            public void set(String value) {
              agentServiceProperties.setRuntimeUrl(value);
            }
          });
      setText(
          services,
          "memoryUrl",
          new TextSetter() {
            /**
             * 设置目标值。
             *
             * @param value 输入值
             */
            public void set(String value) {
              agentServiceProperties.setMemoryUrl(value);
            }
          });
      setText(
          services,
          "toolUrl",
          new TextSetter() {
            /**
             * 设置目标值。
             *
             * @param value 输入值
             */
            public void set(String value) {
              agentServiceProperties.setToolUrl(value);
            }
          });
      setText(
          services,
          "evalUrl",
          new TextSetter() {
            /**
             * 设置目标值。
             *
             * @param value 输入值
             */
            public void set(String value) {
              agentServiceProperties.setEvalUrl(value);
            }
          });
      setText(
          services,
          "sandboxUrl",
          new TextSetter() {
            /**
             * 设置目标值。
             *
             * @param value 输入值
             */
            public void set(String value) {
              agentServiceProperties.setSandboxUrl(value);
            }
          });
      Duration connectTimeout = durationValue(services.get("connectTimeout"));
      if (connectTimeout != null) agentServiceProperties.setConnectTimeout(connectTimeout);
      Duration readTimeout = durationValue(services.get("readTimeout"));
      if (readTimeout != null) agentServiceProperties.setReadTimeout(readTimeout);
    }
  }

  /**
   * 获取移除 Runtime 地址后的设置。
   *
   * @return 设置不含 Runtime
   */
  private Map<String, Object> getSettingsWithoutRuntime() {
    Map<String, Object> settings = getSettingsMap();
    settings.remove("runtime");
    settings.remove("serviceStatuses");
    settings.remove("settingsPath");
    return settings;
  }

  /**
   * 应用全局记忆策略。
   *
   * @param settings 设置
   */
  @SuppressWarnings("unchecked")
  private void enforceGlobalMemoryPolicy(Map<String, Object> settings) {
    if (settings == null) return;
    Object value = settings.get("memory");
    if (!(value instanceof Map)) return;
    Map<String, Object> memory = new LinkedHashMap<String, Object>((Map<String, Object>) value);
    memory.put("items", new ArrayList<Map<String, Object>>());
    settings.put("memory", memory);
  }

  /**
   * 获取记忆策略。
   *
   * @return 记忆策略
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> memoryPolicy() {
    Map<String, Object> settings = getSettingsWithoutRuntime();
    Object value = settings.get("memory");
    return value instanceof Map
        ? new LinkedHashMap<String, Object>((Map<String, Object>) value)
        : memoryDefaults();
  }

  /**
   * 迁移旧版数据记忆。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   */
  private void migrateLegacyMemories(String tenantId, String userId) {
    List<Map<String, Object>> legacyItems = readLegacyMemoryItems(tenantId, userId);
    if (legacyItems.isEmpty()) return;
    List<SystemMemoryResponse> existing = agentMemoryClient.list(tenantId, userId);
    for (Map<String, Object> item : legacyItems) {
      SystemMemoryRequest request =
          normalizeMemoryRequest(jsonCodec.convert(item, SystemMemoryRequest.class), "legacy");
      if (findSameMemory(existing, request) != null) continue;
      SystemMemoryResponse created = agentMemoryClient.create(tenantId, userId, request);
      existing.add(created);
    }
    systemSettingsMapper.deleteSetting(memoryScope(tenantId, userId), USER_MEMORY_KEY);
  }

  /**
   * 读取旧版数据记忆数据项。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 旧版记忆列表
   */
  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> readLegacyMemoryItems(String tenantId, String userId) {
    requireMemoryOwner(tenantId, userId);
    try {
      String json =
          systemSettingsMapper.findSettingJson(memoryScope(tenantId, userId), USER_MEMORY_KEY);
      if (json == null || json.trim().isEmpty()) return new ArrayList<Map<String, Object>>();
      Map<String, Object> saved =
          objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
      Object items = saved.get("items");
      return items instanceof List
          ? new ArrayList<Map<String, Object>>((List<Map<String, Object>>) items)
          : new ArrayList<Map<String, Object>>();
    } catch (Exception e) {
      throw new RuntimeException("读取待迁移的用户长期记忆失败: " + e.getMessage(), e);
    }
  }

  /**
   * 校验并获取记忆属主。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   */
  private void requireMemoryOwner(String tenantId, String userId) {
    if (tenantId == null
        || tenantId.trim().isEmpty()
        || userId == null
        || userId.trim().isEmpty()) {
      throw new IllegalArgumentException("长期记忆读写必须提供 tenantId 和 userId");
    }
  }

  /**
   * 获取记忆作用域。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 记忆作用域
   */
  private String memoryScope(String tenantId, String userId) {
    requireMemoryOwner(tenantId, userId);
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes =
          digest.digest(
              (tenantId.trim() + "\u0000" + userId.trim()).getBytes(StandardCharsets.UTF_8));
      StringBuilder value = new StringBuilder(USER_MEMORY_SCOPE_PREFIX);
      for (byte b : bytes) value.append(String.format("%02x", b & 0xff));
      return value.toString();
    } catch (Exception e) {
      throw new IllegalStateException("无法生成用户记忆作用域", e);
    }
  }

  /**
   * 规范化记忆请求。
   *
   * @param request 请求对象
   * @param defaultSource 默认源
   * @return 规范化后的记忆请求
   */
  private SystemMemoryRequest normalizeMemoryRequest(
      SystemMemoryRequest request, String defaultSource) {
    if (request == null || request.getContent() == null || request.getContent().trim().isEmpty()) {
      throw new IllegalArgumentException("记忆内容不能为空");
    }
    SystemMemoryRequest normalized = new SystemMemoryRequest();
    normalized.setContent(request.getContent().trim());
    normalized.setSource(
        request.getSource() == null || request.getSource().trim().isEmpty()
            ? defaultSource
            : request.getSource().trim());
    normalized.setEnabled(request.getEnabled() == null ? Boolean.TRUE : request.getEnabled());
    return normalized;
  }

  /**
   * 查询相同数据记忆。
   *
   * @param items 数据项列表
   * @param target 待匹配的记忆
   * @return 相同数据记忆
   */
  private SystemMemoryResponse findSameMemory(
      List<SystemMemoryResponse> items, SystemMemoryRequest target) {
    String targetKey = memoryKey(target.getContent());
    for (SystemMemoryResponse item : items) {
      if (targetKey.equals(memoryKey(item.getContent()))) return item;
    }
    return null;
  }

  /**
   * 裁剪记忆。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param existing 已存在数量
   * @param created 新建数量
   */
  private void trimMemories(
      String tenantId,
      String userId,
      List<SystemMemoryResponse> existing,
      SystemMemoryResponse created) {
    Integer configured = intValue(memoryPolicy().get("maxItems"));
    int max = configured == null ? 200 : Math.max(1, configured.intValue());
    List<SystemMemoryResponse> ordered = new ArrayList<SystemMemoryResponse>();
    ordered.add(created);
    for (SystemMemoryResponse item : existing) {
      if (!created.getId().equals(item.getId())) ordered.add(item);
    }
    for (int index = max; index < ordered.size(); index++) {
      agentMemoryClient.delete(tenantId, userId, ordered.get(index).getId());
    }
  }

  /**
   * 获取记忆键。
   *
   * @param content 内容
   * @return 记忆键
   */
  private String memoryKey(String content) {
    return normalizeMemoryText(content);
  }

  /**
   * 规范化记忆文本。
   *
   * @param value 输入值
   * @return 规范化后的记忆文本
   */
  private String normalizeMemoryText(String value) {
    if (value == null) return "";
    return value
        .toLowerCase()
        .replaceAll("[\\s　]+", "")
        .replace('，', ',')
        .replace('。', '.')
        .replace('；', ';')
        .replace('：', ':')
        .trim();
  }

  /**
   * 判断是否自动写入记忆。
   *
   * @param content 内容
   * @return 是否自动写入记忆
   */
  private boolean shouldAutoWriteMemory(String content) {
    String text = content == null ? "" : content.trim();
    // 文本是否为稳定信号由 ChatSseSupport.shouldCaptureLongTermMemory 统一判断；此处只保留最终写入边界。
    return normalizeMemoryText(text).length() >= 4;
  }

  /**
   * 获取布尔值。
   *
   * @param value 输入值
   * @param fallback 降级结果
   * @return 布尔值
   */
  private boolean booleanValue(Object value, boolean fallback) {
    if (value instanceof Boolean) return ((Boolean) value).booleanValue();
    if (value == null) return fallback;
    String text = String.valueOf(value);
    return "true".equalsIgnoreCase(text) || "1".equals(text);
  }

  /**
   * 读取已保存设置。
   *
   * @return 已保存设置
   */
  private Map<String, Object> readSavedSettings() {
    try {
      String json = systemSettingsMapper.findSettingJson(SETTINGS_SCOPE, SETTINGS_KEY);
      if (json == null || json.trim().isEmpty()) return new LinkedHashMap<String, Object>();
      return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      throw new RuntimeException("读取 PostgreSQL 平台设置失败: " + e.getMessage(), e);
    }
  }

  /**
   * 清理并校验系统设置。
   *
   * @param payload 请求载荷
   * @return 清洗后的数据
   */
  private Map<String, Object> sanitize(Map<String, Object> payload) {
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    if (payload == null) return result;
    copySanitizedWorkspace(payload, result);
    copySanitizedServices(payload, result);
    copySanitizedMemoryPolicy(payload, result);
    copyIfMap(payload, result, "blacklist");
    return result;
  }

  /**
   * 写入设置。
   *
   * @param settings 设置
   */
  private void writeSettings(Map<String, Object> settings) {
    try {
      systemSettingsMapper.upsertSetting(
          SETTINGS_SCOPE,
          SETTINGS_KEY,
          objectMapper.writeValueAsString(
              settings == null ? new LinkedHashMap<String, Object>() : settings));
    } catch (Exception e) {
      throw new RuntimeException("保存 PostgreSQL 平台设置失败: " + e.getMessage(), e);
    }
  }

  /**
   * 仅复制映射类型字段。
   *
   * @param from 来源
   * @param to 目标
   * @param key 业务键
   */
  private void copyIfMap(Map<String, Object> from, Map<String, Object> to, String key) {
    Object value = from.get(key);
    if (value instanceof Map) to.put(key, value);
  }

  /**
   * 复制已清理的记忆策略。
   *
   * @param from 来源
   * @param to 目标
   */
  @SuppressWarnings("unchecked")
  private void copySanitizedMemoryPolicy(Map<String, Object> from, Map<String, Object> to) {
    Object value = from.get("memory");
    if (!(value instanceof Map)) return;
    Map<String, Object> source = (Map<String, Object>) value;
    Map<String, Object> policy = new LinkedHashMap<String, Object>();
    copyIfPresent(source, policy, "enabled");
    copyIfPresent(source, policy, "autoSaveChat");
    copyIfPresent(source, policy, "autoUseMemory");
    copyIfPresent(source, policy, "maxItems");
    to.put("memory", policy);
  }

  /**
   * 仅复制非空配置项。
   *
   * @param from 来源
   * @param to 目标
   * @param key 业务键
   */
  private void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String key) {
    if (from.containsKey(key)) to.put(key, from.get(key));
  }

  /**
   * 复制已清理的工作区配置。
   *
   * @param from 来源
   * @param to 目标
   */
  @SuppressWarnings("unchecked")
  private void copySanitizedWorkspace(Map<String, Object> from, Map<String, Object> to) {
    Object value = from.get("workspace");
    if (!(value instanceof Map)) return;
    Map<String, Object> source = (Map<String, Object>) value;
    Map<String, Object> workspace = new LinkedHashMap<String, Object>();
    for (String key : WORKSPACE_SETTING_KEYS) {
      if (source.containsKey(key)) workspace.put(key, source.get(key));
    }
    to.put("workspace", workspace);
  }

  /**
   * 复制已清理的服务配置。
   *
   * @param from 来源
   * @param to 目标
   */
  @SuppressWarnings("unchecked")
  private void copySanitizedServices(Map<String, Object> from, Map<String, Object> to) {
    Object value = from.get("services");
    if (!(value instanceof Map)) return;
    Map<String, Object> services = new LinkedHashMap<String, Object>((Map<String, Object>) value);
    sanitizeServiceUrl(services, "intentUrl");
    sanitizeServiceUrl(services, "runtimeUrl");
    sanitizeServiceUrl(services, "memoryUrl");
    sanitizeServiceUrl(services, "toolUrl");
    sanitizeServiceUrl(services, "evalUrl");
    sanitizeServiceUrl(services, "sandboxUrl");
    to.put("services", services);
  }

  /**
   * 清理并校验服务地址。
   *
   * @param services 服务配置列表
   * @param key 业务键
   */
  private void sanitizeServiceUrl(Map<String, Object> services, String key) {
    if (!services.containsKey(key)) return;
    String value = stringValue(services.get(key));
    if (value == null || value.trim().isEmpty()) {
      services.put(key, "");
      return;
    }
    services.put(key, normalizeLoopbackHttpUrl(value, key));
  }

  /**
   * 规范化回环 HTTP 地址。
   *
   * @param rawValue 原始值
   * @param key 业务键
   * @return 规范化后的回环 HTTP 地址
   */
  private String normalizeLoopbackHttpUrl(String rawValue, String key) {
    String value = rawValue.trim();
    try {
      URI uri = URI.create(value);
      String scheme = uri.getScheme();
      String host = uri.getHost();
      if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
        throw new IllegalArgumentException(key + " 仅支持 http/https 服务地址");
      }
      if (uri.getUserInfo() != null) {
        throw new IllegalArgumentException(key + " 不允许包含用户信息");
      }
      if (!isLoopbackHost(host)) {
        throw new IllegalArgumentException(key + " 仅允许指向本机 loopback 地址");
      }
      String normalized = uri.toString();
      while (normalized.endsWith("/"))
        normalized = normalized.substring(0, normalized.length() - 1);
      return normalized;
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException(key + " 服务地址不合法", e);
    }
  }

  /**
   * 判断是否为回环主机。
   *
   * @param host 主机名
   * @return 是否为回环主机
   */
  private boolean isLoopbackHost(String host) {
    if (host == null || host.trim().isEmpty()) return false;
    String value = host.trim().toLowerCase();
    return "localhost".equals(value)
        || "127.0.0.1".equals(value)
        || value.startsWith("127.")
        || "::1".equals(value)
        || "0:0:0:0:0:0:0:1".equals(value);
  }

  /**
   * 递归合并嵌套配置。
   *
   * @param target 合并后的配置
   * @param source 源数据
   */
  @SuppressWarnings("unchecked")
  private void deepMerge(Map<String, Object> target, Map<String, Object> source) {
    if (source == null) return;
    for (Map.Entry<String, Object> entry : source.entrySet()) {
      Object existing = target.get(entry.getKey());
      Object incoming = entry.getValue();
      if (existing instanceof Map && incoming instanceof Map)
        deepMerge((Map<String, Object>) existing, (Map<String, Object>) incoming);
      else target.put(entry.getKey(), incoming);
    }
  }

  /**
   * 应用整数配置项。
   *
   * @param map 数据映射
   * @param key 业务键
   * @param min 最小
   * @param max 最大
   * @param fallback 降级结果
   * @param setter 字段设置函数
   */
  private void applyIntegerSetting(
      Map<String, Object> map, String key, int min, int max, int fallback, IntSetter setter) {
    Integer parsed = intValue(map.get(key));
    int normalized = clamp(parsed == null ? fallback : parsed.intValue(), min, max);
    map.put(key, Integer.valueOf(normalized));
    setter.set(normalized);
  }

  /**
   * 设置文本。
   *
   * @param map 数据映射
   * @param key 业务键
   * @param setter 字段设置函数
   */
  private void setText(Map<String, Object> map, String key, TextSetter setter) {
    String value = stringValue(map.get(key));
    if (value != null) setter.set(value.trim());
  }

  /**
   * 定义整数配置写入器。
   */
  private interface IntSetter {
    /**
     * 设置目标值。
     *
     * @param value 输入值
     */
    void set(int value);
  }

  /**
   * 定义文本写入器。
   */
  private interface TextSetter {
    /**
     * 设置目标值。
     *
     * @param value 输入值
     */
    void set(String value);
  }

  /**
   * 获取字符串值。
   *
   * @param value 输入值
   * @return 字符串值
   */
  private String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  /**
   * 获取整数值。
   *
   * @param value 输入值
   * @return 整数值
   */
  private Integer intValue(Object value) {
    if (value instanceof Number) return Integer.valueOf(((Number) value).intValue());
    if (value == null) return null;
    try {
      return Integer.valueOf(String.valueOf(value));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * 将数值约束在上下限内。
   *
   * @param value 输入值
   * @param min 最小
   * @param max 最大
   * @return 限制范围后的数值
   */
  private int clamp(int value, int min, int max) {
    return Math.min(max, Math.max(min, value));
  }

  /**
   * 获取时长配置。
   *
   * @param value 输入值
   * @return 时长配置
   */
  private Duration durationValue(Object value) {
    if (value instanceof Duration) return (Duration) value;
    if (value == null) return null;
    String text = String.valueOf(value).trim();
    if (text.isEmpty()) return null;
    try {
      if (text.startsWith("PT")) return Duration.parse(text);
      if (text.endsWith("ms"))
        return Duration.ofMillis(Long.parseLong(text.substring(0, text.length() - 2)));
      if (text.endsWith("s"))
        return Duration.ofSeconds(Long.parseLong(text.substring(0, text.length() - 1)));
      if (text.endsWith("m"))
        return Duration.ofMinutes(Long.parseLong(text.substring(0, text.length() - 1)));
      return Duration.ofSeconds(Long.parseLong(text));
    } catch (Exception e) {
      return null;
    }
  }
}
