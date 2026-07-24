package com.jobbuddy.backend.modules.system.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.util.JsonCodec;
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
import java.util.UUID;
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
  private static final int MIN_JOBS_PER_SCORING = 10;
  private static final int MAX_JOBS_PER_SCORING = 200;
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
    "maxJobsPerScoring",
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
  private final JsonCodec jsonCodec = new JsonCodec();
  private final ServiceHealthMonitor serviceHealthMonitor;
  private final JobBlacklistPolicy blacklistPolicy;
  private boolean persistedRuntimeSettingsLoaded;

  public SystemSettingsServiceImpl(
      AgentServiceProperties agentServiceProperties,
      JobBuddyProperties jobBuddyProperties,
      SystemSettingsMapper systemSettingsMapper) {
    this.agentServiceProperties = agentServiceProperties;
    this.jobBuddyProperties = jobBuddyProperties;
    this.workspaceDefaultSettings = workspaceSettingsFromProperties();
    this.systemSettingsMapper = systemSettingsMapper;
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
    return jsonCodec.convertList(listMemoriesMap(tenantId, userId), SystemMemoryResponse.class);
  }

  private synchronized List<Map<String, Object>> listMemoriesMap(String tenantId, String userId) {
    Map<String, Object> memory = readUserMemory(tenantId, userId);
    List<Map<String, Object>> items = memoryItems(memory);
    if (dedupeMemories(items)) writeUserMemory(tenantId, userId, memory);
    List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
    for (Map<String, Object> item : items) result.add(new LinkedHashMap<String, Object>(item));
    return result;
  }

  public synchronized SystemMemoryResponse addMemory(
      String tenantId, String userId, SystemMemoryRequest request) {
    Map<String, Object> payload = jsonCodec.toMap(request);
    Map<String, Object> memory = readUserMemory(tenantId, userId);
    List<Map<String, Object>> items = memoryItems(memory);
    Map<String, Object> item =
        normalizeMemory(payload == null ? new LinkedHashMap<String, Object>() : payload, true);
    Map<String, Object> existing = findSameMemory(items, item);
    if (existing != null) {
      mergeMemory(existing, item);
      moveMemoryToTop(items, existing);
      dedupeMemories(items);
      writeUserMemory(tenantId, userId, memory);
      return jsonCodec.convert(
          new LinkedHashMap<String, Object>(existing), SystemMemoryResponse.class);
    }
    items.add(0, item);
    dedupeMemories(items);
    trimMemories(memory, items);
    writeUserMemory(tenantId, userId, memory);
    return jsonCodec.convert(item, SystemMemoryResponse.class);
  }

  public synchronized void writeLocalMemory(
      String tenantId, String userId, String type, String content, String source) {
    requireMemoryOwner(tenantId, userId);
    if (content == null || content.trim().length() < 2) return;
    if (!shouldAutoWriteMemory(type, content)) return;
    Map<String, Object> memory = readUserMemory(tenantId, userId);
    if (!booleanValue(memory.get("enabled"), true)
        || !booleanValue(memory.get("autoSaveChat"), false)) return;
    List<Map<String, Object>> items = memoryItems(memory);
    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("type", type == null ? "conversation" : type);
    payload.put("content", content.trim());
    payload.put("source", source == null ? "chat" : source);
    payload.put("enabled", true);
    Map<String, Object> item = normalizeMemory(payload, true);
    Map<String, Object> existing = findSameMemory(items, item);
    if (existing != null) {
      mergeMemory(existing, item);
      moveMemoryToTop(items, existing);
    } else {
      items.add(0, item);
    }
    dedupeMemories(items);
    trimMemories(memory, items);
    writeUserMemory(tenantId, userId, memory);
  }

  public synchronized void deleteMemory(String tenantId, String userId, String memoryId) {
    Map<String, Object> memory = readUserMemory(tenantId, userId);
    List<Map<String, Object>> items = memoryItems(memory);
    items.removeIf(item -> memoryId != null && memoryId.equals(String.valueOf(item.get("id"))));
    writeUserMemory(tenantId, userId, memory);
  }

  public synchronized int clearMemories(String tenantId, String userId) {
    Map<String, Object> memory = readUserMemory(tenantId, userId);
    List<Map<String, Object>> items = memoryItems(memory);
    int count = items.size();
    items.clear();
    writeUserMemory(tenantId, userId, memory);
    return count;
  }

  public synchronized List<SystemMemoryResponse> searchLocalMemories(
      String tenantId, String userId, String query, int limit) {
    Map<String, Object> memory = readUserMemory(tenantId, userId);
    if (!booleanValue(memory.get("enabled"), true)
        || !booleanValue(memory.get("autoUseMemory"), true))
      return new ArrayList<SystemMemoryResponse>();
    String normalizedQuery = normalizeMemoryText(query);
    List<String> queryTokens = memoryTokens(query);
    if (normalizedQuery.length() < 4 || queryTokens.isEmpty())
      return new ArrayList<SystemMemoryResponse>();
    List<Map<String, Object>> scored = new ArrayList<Map<String, Object>>();
    for (Map<String, Object> item : listMemoriesMap(tenantId, userId)) {
      if (!booleanValue(item.get("enabled"), true)) continue;
      if ("conversation".equalsIgnoreCase(String.valueOf(item.get("type")))) continue;
      String content = String.valueOf(item.get("content"));
      MemoryScore score = scoreMemory(queryTokens, normalizedQuery, content);
      if (score.score < 2) continue;
      Map<String, Object> copy = new LinkedHashMap<String, Object>(item);
      copy.put("scope", "user");
      copy.put("score", score.score);
      copy.put("matchReasons", score.reasons);
      scored.add(copy);
    }
    scored.sort(
        new java.util.Comparator<Map<String, Object>>() {
          @Override
          public int compare(Map<String, Object> a, Map<String, Object> b) {
            return Integer.compare(intValueOrZero(b.get("score")), intValueOrZero(a.get("score")));
          }
        });
    int max = Math.max(0, Math.min(Math.max(1, limit), 2));
    List<Map<String, Object>> limited =
        scored.size() > max ? new ArrayList<Map<String, Object>>(scored.subList(0, max)) : scored;
    return jsonCodec.convertList(limited, SystemMemoryResponse.class);
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
    data.put("maxJobsPerScoring", jobBuddyProperties.getMaxJobsPerScoring());
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
          "maxJobsPerScoring",
          MIN_JOBS_PER_SCORING,
          MAX_JOBS_PER_SCORING,
          jobBuddyProperties.getMaxJobsPerScoring(),
          jobBuddyProperties::setMaxJobsPerScoring);
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
  private Map<String, Object> readUserMemory(String tenantId, String userId) {
    requireMemoryOwner(tenantId, userId);
    Map<String, Object> globalSettings = getSettingsWithoutRuntime();
    Map<String, Object> policy =
        new LinkedHashMap<String, Object>(asMap(globalSettings.get("memory"), memoryDefaults()));
    policy.put("items", new ArrayList<Map<String, Object>>());
    try {
      String json =
          systemSettingsMapper.findSettingJson(memoryScope(tenantId, userId), USER_MEMORY_KEY);
      if (json == null || json.trim().isEmpty()) return policy;
      Map<String, Object> saved =
          objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
      Object items = saved.get("items");
      if (items instanceof List)
        policy.put("items", new ArrayList<Map<String, Object>>((List<Map<String, Object>>) items));
      return policy;
    } catch (Exception e) {
      throw new RuntimeException("读取用户长期记忆失败: " + e.getMessage(), e);
    }
  }

  private void writeUserMemory(String tenantId, String userId, Map<String, Object> memory) {
    requireMemoryOwner(tenantId, userId);
    Map<String, Object> document = new LinkedHashMap<String, Object>();
    document.put("items", new ArrayList<Map<String, Object>>(memoryItems(memory)));
    document.put("updatedAt", Instant.now().toString());
    try {
      systemSettingsMapper.upsertSetting(
          memoryScope(tenantId, userId),
          USER_MEMORY_KEY,
          objectMapper.writeValueAsString(document));
    } catch (Exception e) {
      throw new RuntimeException("保存用户长期记忆失败: " + e.getMessage(), e);
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

  @SuppressWarnings("unchecked")
  private Map<String, Object> asMap(Object value, Map<String, Object> fallback) {
    return value instanceof Map ? (Map<String, Object>) value : fallback;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> memoryItems(Map<String, Object> memory) {
    Object items = memory.get("items");
    if (items instanceof List) return (List<Map<String, Object>>) items;
    List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
    memory.put("items", list);
    return list;
  }

  private Map<String, Object> normalizeMemory(Map<String, Object> payload, boolean newId) {
    Map<String, Object> item = new LinkedHashMap<String, Object>();
    Object id = payload.get("id");
    item.put(
        "id",
        id == null || newId
            ? "mem_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
            : String.valueOf(id));
    item.put(
        "type", payload.get("type") == null ? "preference" : String.valueOf(payload.get("type")));
    item.put(
        "content", payload.get("content") == null ? "" : String.valueOf(payload.get("content")));
    item.put(
        "source", payload.get("source") == null ? "manual" : String.valueOf(payload.get("source")));
    item.put("enabled", Boolean.valueOf(booleanValue(payload.get("enabled"), true)));
    item.put(
        "createdAt",
        payload.get("createdAt") == null
            ? Instant.now().toString()
            : String.valueOf(payload.get("createdAt")));
    return item;
  }

  private void trimMemories(Map<String, Object> memory, List<Map<String, Object>> items) {
    Integer maxValue = intValue(memory.get("maxItems"));
    int max = maxValue == null ? 200 : Math.max(1, maxValue.intValue());
    while (items.size() > max) items.remove(items.size() - 1);
  }

  private boolean dedupeMemories(List<Map<String, Object>> items) {
    if (items == null || items.size() < 2) return false;
    Map<String, Map<String, Object>> seen = new LinkedHashMap<String, Map<String, Object>>();
    boolean changed = false;
    for (int i = 0; i < items.size(); i++) {
      Map<String, Object> item = items.get(i);
      String key = memoryKey(item);
      if (key.isEmpty()) continue;
      Map<String, Object> existing = seen.get(key);
      if (existing == null) {
        seen.put(key, item);
      } else {
        mergeMemory(existing, item);
        items.remove(i);
        i--;
        changed = true;
      }
    }
    return changed;
  }

  private Map<String, Object> findSameMemory(
      List<Map<String, Object>> items, Map<String, Object> target) {
    if (items == null || target == null) return null;
    String targetKey = memoryKey(target);
    if (targetKey.isEmpty()) return null;
    for (Map<String, Object> item : items) if (targetKey.equals(memoryKey(item))) return item;
    return null;
  }

  private void mergeMemory(Map<String, Object> existing, Map<String, Object> incoming) {
    if (existing == null || incoming == null) return;
    existing.put(
        "content",
        incoming.get("content") == null ? existing.get("content") : incoming.get("content"));
    existing.put(
        "type", incoming.get("type") == null ? existing.get("type") : incoming.get("type"));
    existing.put(
        "source", incoming.get("source") == null ? existing.get("source") : incoming.get("source"));
    existing.put(
        "enabled",
        Boolean.valueOf(
            booleanValue(incoming.get("enabled"), booleanValue(existing.get("enabled"), true))));
    if (existing.get("createdAt") == null && incoming.get("createdAt") != null)
      existing.put("createdAt", incoming.get("createdAt"));
    existing.put("updatedAt", Instant.now().toString());
  }

  private void moveMemoryToTop(List<Map<String, Object>> items, Map<String, Object> item) {
    if (items == null || item == null) return;
    if (items.remove(item)) items.add(0, item);
  }

  private String memoryKey(Map<String, Object> item) {
    if (item == null) return "";
    String type =
        normalizeMemoryText(
            String.valueOf(item.get("type") == null ? "preference" : item.get("type")));
    String content =
        normalizeMemoryText(String.valueOf(item.get("content") == null ? "" : item.get("content")));
    if (content.isEmpty()) return "";
    return type + "|" + content;
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

  private MemoryScore scoreMemory(
      List<String> queryTokens, String normalizedQuery, String content) {
    MemoryScore result = new MemoryScore();
    String normalizedContent = normalizeMemoryText(content);
    if (normalizedContent.isEmpty()) return result;
    for (String token : queryTokens) {
      if (token.length() < 2) continue;
      if (normalizedContent.contains(token)) {
        result.score += token.length() >= 4 ? 2 : 1;
        result.reasons.add(token);
      }
    }
    if (normalizedQuery.length() >= 8 && normalizedContent.contains(normalizedQuery)) {
      result.score += 3;
      result.reasons.add("exact_query");
    }
    return result;
  }

  private List<String> memoryTokens(String value) {
    List<String> tokens = new ArrayList<String>();
    String text = value == null ? "" : value.toLowerCase();
    java.util.regex.Matcher ascii =
        java.util.regex.Pattern.compile("[a-z0-9_+#.-]{2,}").matcher(text);
    while (ascii.find()) addToken(tokens, ascii.group());
    java.util.regex.Matcher chinese =
        java.util.regex.Pattern.compile("[\\u4e00-\\u9fff]{2,}").matcher(text);
    while (chinese.find()) {
      String chunk = chinese.group();
      if (chunk.length() <= 4) {
        addToken(tokens, chunk);
      } else {
        for (int i = 0; i < chunk.length() - 1; i++) addToken(tokens, chunk.substring(i, i + 2));
      }
    }
    return tokens;
  }

  private void addToken(List<String> tokens, String token) {
    if (token == null) return;
    String value = normalizeMemoryText(token);
    if (value.length() < 2 || isWeakMemoryToken(value)) return;
    if (!tokens.contains(value)) tokens.add(value);
  }

  private boolean isWeakMemoryToken(String token) {
    return token.length() < 2
        || "分析".equals(token)
        || "当前".equals(token)
        || "是否".equals(token)
        || "帮我".equals(token)
        || "岗位".equals(token)
        || "职位".equals(token)
        || "简历".equals(token)
        || "这个".equals(token)
        || "这些".equals(token)
        || "什么".equals(token)
        || "怎么".equals(token)
        || "如何".equals(token);
  }

  private boolean shouldAutoWriteMemory(String type, String content) {
    String memoryType = type == null ? "" : type.trim().toLowerCase();
    if (!"preference".equals(memoryType) && !"constraint".equals(memoryType)) return false;
    String text = content == null ? "" : content.trim();
    // 文本是否为稳定信号由 ChatSseSupport.classifyMemoryType 统一判断；此处只保留最终写入边界，避免重复关键词规则漂移。
    return normalizeMemoryText(text).length() >= 4;
  }

  private int intValueOrZero(Object value) {
    Integer parsed = intValue(value);
    return parsed == null ? 0 : parsed.intValue();
  }

  private static class MemoryScore {
    int score = 0;
    List<String> reasons = new ArrayList<String>();
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
