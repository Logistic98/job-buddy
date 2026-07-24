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

  /** 在接收业务请求前加载持久化运行参数，避免首个请求短暂使用部署默认值。 */
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

  public synchronized SystemSettingsResponse getSettings() {
    return jsonCodec.convert(getSettingsMap(), SystemSettingsResponse.class);
  }

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

  public synchronized SystemSettingsResponse restoreWorkspaceDefaults() {
    Map<String, Object> saved = readSavedSettings();
    saved.remove("workspace");
    saved.put("updatedAt", Instant.now().toString());
    writeSettings(saved);
    return getSettings();
  }

  public synchronized List<SystemMemoryResponse> listMemories(String tenantId, String userId) {
    migrateLegacyMemories(tenantId, userId);
    return agentMemoryClient.list(tenantId, userId);
  }

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

  public synchronized void writeLocalMemory(
      String tenantId, String userId, String type, String content, String source) {
    requireMemoryOwner(tenantId, userId);
    if (content == null || content.trim().length() < 2) return;
    if (!shouldAutoWriteMemory(type, content)) return;
    Map<String, Object> policy = memoryPolicy();
    if (!booleanValue(policy.get("enabled"), true)
        || !booleanValue(policy.get("autoSaveChat"), false)) return;
    migrateLegacyMemories(tenantId, userId);
    SystemMemoryRequest request = new SystemMemoryRequest();
    request.setType(type);
    request.setContent(content);
    request.setSource(source);
    request.setEnabled(Boolean.TRUE);
    addMemory(tenantId, userId, request);
  }

  public synchronized void deleteMemory(String tenantId, String userId, String memoryId) {
    migrateLegacyMemories(tenantId, userId);
    agentMemoryClient.delete(tenantId, userId, memoryId);
  }

  public synchronized int clearMemories(String tenantId, String userId) {
    migrateLegacyMemories(tenantId, userId);
    return agentMemoryClient.clear(tenantId, userId);
  }

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

  private Map<String, Object> defaultSettings() {
    Map<String, Object> root = new LinkedHashMap<String, Object>();
    root.put("workspace", workspaceDefaults());
    root.put("services", serviceDefaults());
    root.put("memory", memoryDefaults());
    root.put("blacklist", blacklistDefaults());
    root.put("updatedAt", null);
    return root;
  }

  private Map<String, Object> workspaceDefaults() {
    return new LinkedHashMap<String, Object>(workspaceDefaultSettings);
  }

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

  private Map<String, Object> memoryDefaults() {
    Map<String, Object> data = new LinkedHashMap<String, Object>();
    data.put("enabled", true);
    data.put("autoSaveChat", false);
    data.put("autoUseMemory", true);
    data.put("maxItems", 200);
    data.put("items", new ArrayList<Map<String, Object>>());
    return data;
  }

  private Map<String, Object> blacklistDefaults() {
    return blacklistPolicy.defaults();
  }

  @SuppressWarnings("unchecked")
  public synchronized List<SystemSettingsResponse.Item> listBlacklistItems() {
    return jsonCodec.convertList(listBlacklistItemsMap(), SystemSettingsResponse.Item.class);
  }

  @SuppressWarnings("unchecked")
  private synchronized List<Map<String, Object>> listBlacklistItemsMap() {
    return blacklistPolicy.listItems(getSettingsMap());
  }

  @SuppressWarnings("unchecked")
  private void applyBlacklistItems(
      Map<String, Object> settings, Map<String, Object> savedSettings) {
    blacklistPolicy.applyItems(settings, savedSettings);
  }

  public synchronized boolean isBlacklistedJob(Map<String, Object> job) {
    return blacklistPolicy.isBlacklisted(job, readSavedSettings());
  }

  public synchronized List<Map<String, Object>> filterBlacklistedJobs(
      List<Map<String, Object>> jobs) {
    return blacklistPolicy.filter(jobs, readSavedSettings());
  }

  private Map<String, Object> serviceDefaults() {
    return serviceHealthMonitor.serviceDefaults();
  }

  private Map<String, Object> runtimeSettings() {
    return serviceHealthMonitor.runtimeSettings();
  }

  private synchronized Map<String, Object> serviceStatuses() {
    return serviceHealthMonitor.statuses();
  }

  @Scheduled(
      fixedDelayString = "${job-buddy.service-monitor.interval-ms:10000}",
      initialDelayString = "${job-buddy.service-monitor.initial-delay-ms:0}")
  public void monitorServiceHealth() {
    refreshServiceStatuses();
  }

  @Override
  public synchronized ServiceStatusesResponse refreshServiceStatuses() {
    ensurePersistedRuntimeSettingsLoaded();
    return serviceHealthMonitor.refresh();
  }

  private void ensurePersistedRuntimeSettingsLoaded() {
    if (persistedRuntimeSettingsLoaded) return;
    Map<String, Object> settings = defaultSettings();
    deepMerge(settings, readSavedSettings());
    retainBusinessRuntimeSettings(settings);
    applyRuntimeSettings(settings);
    persistedRuntimeSettingsLoaded = true;
  }

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
            public void set(String value) {
              agentServiceProperties.setIntentUrl(value);
            }
          });
      setText(
          services,
          "runtimeUrl",
          new TextSetter() {
            public void set(String value) {
              agentServiceProperties.setRuntimeUrl(value);
            }
          });
      setText(
          services,
          "memoryUrl",
          new TextSetter() {
            public void set(String value) {
              agentServiceProperties.setMemoryUrl(value);
            }
          });
      setText(
          services,
          "toolUrl",
          new TextSetter() {
            public void set(String value) {
              agentServiceProperties.setToolUrl(value);
            }
          });
      setText(
          services,
          "evalUrl",
          new TextSetter() {
            public void set(String value) {
              agentServiceProperties.setEvalUrl(value);
            }
          });
      setText(
          services,
          "sandboxUrl",
          new TextSetter() {
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

  private Map<String, Object> getSettingsWithoutRuntime() {
    Map<String, Object> settings = getSettingsMap();
    settings.remove("runtime");
    settings.remove("serviceStatuses");
    settings.remove("settingsPath");
    return settings;
  }

  @SuppressWarnings("unchecked")
  private void enforceGlobalMemoryPolicy(Map<String, Object> settings) {
    if (settings == null) return;
    Object value = settings.get("memory");
    if (!(value instanceof Map)) return;
    Map<String, Object> memory = new LinkedHashMap<String, Object>((Map<String, Object>) value);
    memory.put("items", new ArrayList<Map<String, Object>>());
    settings.put("memory", memory);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> memoryPolicy() {
    Map<String, Object> settings = getSettingsWithoutRuntime();
    Object value = settings.get("memory");
    return value instanceof Map
        ? new LinkedHashMap<String, Object>((Map<String, Object>) value)
        : memoryDefaults();
  }

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

  private void requireMemoryOwner(String tenantId, String userId) {
    if (tenantId == null
        || tenantId.trim().isEmpty()
        || userId == null
        || userId.trim().isEmpty()) {
      throw new IllegalArgumentException("长期记忆读写必须提供 tenantId 和 userId");
    }
  }

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

  private SystemMemoryRequest normalizeMemoryRequest(
      SystemMemoryRequest request, String defaultSource) {
    if (request == null || request.getContent() == null || request.getContent().trim().isEmpty()) {
      throw new IllegalArgumentException("记忆内容不能为空");
    }
    SystemMemoryRequest normalized = new SystemMemoryRequest();
    String type = request.getType() == null ? "" : request.getType().trim().toLowerCase();
    normalized.setType(
        type.matches("preference|constraint|interview|conversation") ? type : "preference");
    normalized.setContent(request.getContent().trim());
    normalized.setSource(
        request.getSource() == null || request.getSource().trim().isEmpty()
            ? defaultSource
            : request.getSource().trim());
    normalized.setEnabled(request.getEnabled() == null ? Boolean.TRUE : request.getEnabled());
    return normalized;
  }

  private SystemMemoryResponse findSameMemory(
      List<SystemMemoryResponse> items, SystemMemoryRequest target) {
    String targetKey = memoryKey(target.getType(), target.getContent());
    for (SystemMemoryResponse item : items) {
      if (targetKey.equals(memoryKey(item.getType(), item.getContent()))) return item;
    }
    return null;
  }

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

  private String memoryKey(String type, String content) {
    String normalizedContent = normalizeMemoryText(content);
    if (normalizedContent.isEmpty()) return "";
    return normalizeMemoryText(type == null ? "preference" : type) + "|" + normalizedContent;
  }

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

  private boolean shouldAutoWriteMemory(String type, String content) {
    String memoryType = type == null ? "" : type.trim().toLowerCase();
    if (!"preference".equals(memoryType) && !"constraint".equals(memoryType)) return false;
    String text = content == null ? "" : content.trim();
    // 文本是否为稳定信号由 ChatSseSupport.classifyMemoryType 统一判断；此处只保留最终写入边界，避免重复关键词规则漂移。
    return normalizeMemoryText(text).length() >= 4;
  }

  private boolean booleanValue(Object value, boolean fallback) {
    if (value instanceof Boolean) return ((Boolean) value).booleanValue();
    if (value == null) return fallback;
    String text = String.valueOf(value);
    return "true".equalsIgnoreCase(text) || "1".equals(text);
  }

  private Map<String, Object> readSavedSettings() {
    try {
      String json = systemSettingsMapper.findSettingJson(SETTINGS_SCOPE, SETTINGS_KEY);
      if (json == null || json.trim().isEmpty()) return new LinkedHashMap<String, Object>();
      return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      throw new RuntimeException("读取 PostgreSQL 平台设置失败: " + e.getMessage(), e);
    }
  }

  private Map<String, Object> sanitize(Map<String, Object> payload) {
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    if (payload == null) return result;
    copySanitizedWorkspace(payload, result);
    copySanitizedServices(payload, result);
    copySanitizedMemoryPolicy(payload, result);
    copyIfMap(payload, result, "blacklist");
    return result;
  }

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

  private void copyIfMap(Map<String, Object> from, Map<String, Object> to, String key) {
    Object value = from.get(key);
    if (value instanceof Map) to.put(key, value);
  }

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

  private void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String key) {
    if (from.containsKey(key)) to.put(key, from.get(key));
  }

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

  private void sanitizeServiceUrl(Map<String, Object> services, String key) {
    if (!services.containsKey(key)) return;
    String value = stringValue(services.get(key));
    if (value == null || value.trim().isEmpty()) {
      services.put(key, "");
      return;
    }
    services.put(key, normalizeLoopbackHttpUrl(value, key));
  }

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

  private boolean isLoopbackHost(String host) {
    if (host == null || host.trim().isEmpty()) return false;
    String value = host.trim().toLowerCase();
    return "localhost".equals(value)
        || "127.0.0.1".equals(value)
        || value.startsWith("127.")
        || "::1".equals(value)
        || "0:0:0:0:0:0:0:1".equals(value);
  }

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

  private void applyIntegerSetting(
      Map<String, Object> map, String key, int min, int max, int fallback, IntSetter setter) {
    Integer parsed = intValue(map.get(key));
    int normalized = clamp(parsed == null ? fallback : parsed.intValue(), min, max);
    map.put(key, Integer.valueOf(normalized));
    setter.set(normalized);
  }

  private void setText(Map<String, Object> map, String key, TextSetter setter) {
    String value = stringValue(map.get(key));
    if (value != null) setter.set(value.trim());
  }

  private interface IntSetter {
    void set(int value);
  }

  private interface TextSetter {
    void set(String value);
  }

  private String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private Integer intValue(Object value) {
    if (value instanceof Number) return Integer.valueOf(((Number) value).intValue());
    if (value == null) return null;
    try {
      return Integer.valueOf(String.valueOf(value));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private int clamp(int value, int min, int max) {
    return Math.min(max, Math.max(min, value));
  }

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
