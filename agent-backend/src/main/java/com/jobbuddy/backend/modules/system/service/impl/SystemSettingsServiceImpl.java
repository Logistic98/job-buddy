package com.jobbuddy.backend.modules.system.service.impl;

import com.jobbuddy.backend.modules.system.service.SystemSettingsService;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.modules.system.mapper.SystemSettingsMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SystemSettingsServiceImpl implements SystemSettingsService {
    private static final String SETTINGS_FILE = "settings.json";
    private static final int HEALTH_TIMEOUT_MILLIS = 1500;
    private static final int MIN_JOBS_PER_RECOMMEND = 1;
    private static final int MAX_JOBS_PER_RECOMMEND = 30;
    private static final int MIN_RESUME_BYTES = 1024 * 1024;
    private static final int MAX_RESUME_BYTES = 20 * 1024 * 1024;

    private final AgentServiceProperties agentServiceProperties;
    private final JobBuddyProperties jobBuddyProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SystemSettingsMapper systemSettingsMapper;
    private final Path settingsPath;

    public SystemSettingsServiceImpl(AgentServiceProperties agentServiceProperties, JobBuddyProperties jobBuddyProperties, SystemSettingsMapper systemSettingsMapper) {
        this.agentServiceProperties = agentServiceProperties;
        this.jobBuddyProperties = jobBuddyProperties;
        this.systemSettingsMapper = systemSettingsMapper;
        this.objectMapper.findAndRegisterModules();
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.settingsPath = Paths.get(System.getProperty("user.home", "."), ".job-buddy", SETTINGS_FILE);
    }

    public synchronized Map<String, Object> getSettings() {
        Map<String, Object> settings = defaultSettings();
        Map<String, Object> saved = readSavedSettings();
        deepMerge(settings, saved);
        applyBlacklistItems(settings, saved);
        applyRuntimeSettings(settings);
        Map<String, Object> statuses = serviceStatuses();
        settings.put("runtime", runtimeSettings());
        settings.put("serviceStatuses", statuses);
        settings.put("settingsPath", settingsPath.toString());
        return settings;
    }

    public synchronized Map<String, Object> saveSettings(Map<String, Object> payload) {
        Map<String, Object> current = getSettingsWithoutRuntime();
        deepMerge(current, sanitize(payload));
        current.put("updatedAt", Instant.now().toString());
        applyRuntimeSettings(current);
        writeSettings(current);
        return getSettings();
    }

    @SuppressWarnings("unchecked")
    public synchronized List<Map<String, Object>> listMemories() {
        Map<String, Object> settings = getSettingsWithoutRuntime();
        Object memoryValue = settings.get("memory");
        Map<String, Object> memory = memoryValue instanceof Map ? (Map<String, Object>) memoryValue : memoryDefaults();
        List<Map<String, Object>> items = memoryItems(memory);
        boolean changed = dedupeMemories(items);
        if (changed) {
            settings.put("updatedAt", Instant.now().toString());
            writeSettings(settings);
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> item : items) result.add(new LinkedHashMap<String, Object>(item));
        return result;
    }

    public synchronized Map<String, Object> addMemory(Map<String, Object> payload) {
        Map<String, Object> settings = getSettingsWithoutRuntime();
        Map<String, Object> memory = ensureMemory(settings);
        List<Map<String, Object>> items = memoryItems(memory);
        Map<String, Object> item = normalizeMemory(payload == null ? new LinkedHashMap<String, Object>() : payload, true);
        Map<String, Object> existing = findSameMemory(items, item);
        if (existing != null) {
            mergeMemory(existing, item);
            moveMemoryToTop(items, existing);
            dedupeMemories(items);
            settings.put("updatedAt", Instant.now().toString());
            writeSettings(settings);
            return new LinkedHashMap<String, Object>(existing);
        }
        items.add(0, item);
        dedupeMemories(items);
        trimMemories(memory, items);
        settings.put("updatedAt", Instant.now().toString());
        writeSettings(settings);
        return item;
    }

    public synchronized void writeLocalMemory(String type, String content, String source) {
        if (content == null || content.trim().length() < 2) return;
        if (!shouldAutoWriteMemory(type, content)) return;
        Map<String, Object> settings = getSettingsWithoutRuntime();
        Map<String, Object> memory = ensureMemory(settings);
        if (!booleanValue(memory.get("enabled"), true) || !booleanValue(memory.get("autoSaveChat"), false)) return;
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
        settings.put("updatedAt", Instant.now().toString());
        writeSettings(settings);
    }

    public synchronized void deleteMemory(String memoryId) {
        Map<String, Object> settings = getSettingsWithoutRuntime();
        List<Map<String, Object>> items = memoryItems(ensureMemory(settings));
        items.removeIf(item -> memoryId != null && memoryId.equals(String.valueOf(item.get("id"))));
        settings.put("updatedAt", Instant.now().toString());
        writeSettings(settings);
    }

    public synchronized int clearMemories() {
        Map<String, Object> settings = getSettingsWithoutRuntime();
        List<Map<String, Object>> items = memoryItems(ensureMemory(settings));
        int count = items.size();
        items.clear();
        settings.put("updatedAt", Instant.now().toString());
        writeSettings(settings);
        return count;
    }

    public synchronized List<Map<String, Object>> searchLocalMemories(String query, int limit) {
        Map<String, Object> settings = getSettings();
        Map<String, Object> memory = asMap(settings.get("memory"), memoryDefaults());
        if (!booleanValue(memory.get("enabled"), true) || !booleanValue(memory.get("autoUseMemory"), true)) return new ArrayList<Map<String, Object>>();
        String normalizedQuery = normalizeMemoryText(query);
        List<String> queryTokens = memoryTokens(query);
        if (normalizedQuery.length() < 4 || queryTokens.isEmpty()) return new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> scored = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> item : listMemories()) {
            if (!booleanValue(item.get("enabled"), true)) continue;
            if ("conversation".equalsIgnoreCase(String.valueOf(item.get("type")))) continue;
            String content = String.valueOf(item.get("content"));
            MemoryScore score = scoreMemory(queryTokens, normalizedQuery, content);
            if (score.score < 2) continue;
            Map<String, Object> copy = new LinkedHashMap<String, Object>(item);
            copy.put("scope", "local");
            copy.put("score", score.score);
            copy.put("matchReasons", score.reasons);
            scored.add(copy);
        }
        scored.sort(new java.util.Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return Integer.compare(intValueOrZero(b.get("score")), intValueOrZero(a.get("score")));
            }
        });
        int max = Math.max(0, Math.min(Math.max(1, limit), 2));
        return scored.size() > max ? new ArrayList<Map<String, Object>>(scored.subList(0, max)) : scored;
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
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("name", "Job Buddy");
        data.put("defaultUserId", jobBuddyProperties.getDefaultUserId());
        data.put("maxJobsPerRecommend", jobBuddyProperties.getMaxJobsPerRecommend());
        data.put("maxResumeBytes", jobBuddyProperties.getMaxResumeBytes());
        data.put("resumeRuntimeWorkspace", jobBuddyProperties.getResumeRuntimeWorkspace());
        return data;
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
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("enabled", true);
        data.put("matchMode", "contains");
        data.put("items", databaseBlacklistItems());
        return data;
    }

    private List<Map<String, Object>> databaseBlacklistItems() {
        try {
            List<Map<String, Object>> rows = systemSettingsMapper.listBlacklistItems();
            for (Map<String, Object> item : rows) {
                Object createdAt = item.get("createdAt");
                if (createdAt instanceof java.sql.Timestamp) {
                    item.put("createdAt", ((java.sql.Timestamp) createdAt).toInstant().toString());
                }
            }
            return rows;
        } catch (Exception e) {
            return new ArrayList<Map<String, Object>>();
        }
    }

    @SuppressWarnings("unchecked")
    public synchronized List<Map<String, Object>> listBlacklistItems() {
        Map<String, Object> settings = getSettings();
        Map<String, Object> blacklist = asMap(settings.get("blacklist"), blacklistDefaults());
        Object items = blacklist.get("items");
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        if (items instanceof List) {
            for (Object item : (List<Object>) items) {
                if (item instanceof Map) result.add(new LinkedHashMap<String, Object>((Map<String, Object>) item));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void applyBlacklistItems(Map<String, Object> settings, Map<String, Object> savedSettings) {
        Map<String, Object> blacklist = asMap(settings.get("blacklist"), blacklistDefaults());
        List<Map<String, Object>> items = databaseBlacklistItems();
        mergeManualBlacklistItems(items, savedSettings);
        blacklist.put("items", items);
        settings.put("blacklist", blacklist);
    }

    @SuppressWarnings("unchecked")
    private void mergeManualBlacklistItems(List<Map<String, Object>> result, Map<String, Object> savedSettings) {
        Object savedBlacklist = savedSettings == null ? null : savedSettings.get("blacklist");
        if (!(savedBlacklist instanceof Map)) return;
        Object savedItems = ((Map<String, Object>) savedBlacklist).get("items");
        if (!(savedItems instanceof List)) return;
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<String, Map<String, Object>>();
        for (Map<String, Object> item : result) byKey.put(String.valueOf(item.get("name")) + "#" + String.valueOf(item.get("type")), item);
        for (Object item : (List<Object>) savedItems) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> row = new LinkedHashMap<String, Object>((Map<String, Object>) item);
            String key = String.valueOf(row.get("name")) + "#" + String.valueOf(row.get("type"));
            Map<String, Object> existing = byKey.get(key);
            if (existing != null) {
                if (row.containsKey("enabled")) existing.put("enabled", row.get("enabled"));
                if (row.containsKey("reason")) existing.put("reason", row.get("reason"));
                continue;
            }
            result.add(row);
            byKey.put(key, row);
        }
    }

    public synchronized boolean isBlacklistedJob(Map<String, Object> job) {
        Map<String, Object> settings = getSettings();
        Map<String, Object> blacklist = asMap(settings.get("blacklist"), blacklistDefaults());
        if (!booleanValue(blacklist.get("enabled"), true) || job == null) return false;
        String text = String.valueOf(job).toLowerCase();
        for (Map<String, Object> item : listBlacklistItems()) {
            if (!booleanValue(item.get("enabled"), true)) continue;
            String name = stringValue(item.get("name"));
            if (name != null && !name.trim().isEmpty() && text.contains(name.trim().toLowerCase())) return true;
        }
        return false;
    }

    public synchronized List<Map<String, Object>> filterBlacklistedJobs(List<Map<String, Object>> jobs) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        if (jobs == null) return result;
        for (Map<String, Object> job : jobs) if (!isBlacklistedJob(job)) result.add(job);
        return result;
    }

    private Map<String, Object> serviceDefaults() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("intentUrl", agentServiceProperties.getIntentUrl());
        data.put("runtimeUrl", agentServiceProperties.getRuntimeUrl());
        data.put("memoryUrl", agentServiceProperties.getMemoryUrl());
        data.put("toolUrl", agentServiceProperties.getToolUrl());
        data.put("evalUrl", agentServiceProperties.getEvalUrl());
        data.put("connectTimeout", agentServiceProperties.getConnectTimeout().toString());
        data.put("readTimeout", agentServiceProperties.getReadTimeout().toString());
        return data;
    }

    private Map<String, Object> runtimeSettings() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("intentUrl", agentServiceProperties.getIntentUrl());
        data.put("runtimeUrl", agentServiceProperties.getRuntimeUrl());
        data.put("memoryUrl", agentServiceProperties.getMemoryUrl());
        data.put("toolUrl", agentServiceProperties.getToolUrl());
        data.put("evalUrl", agentServiceProperties.getEvalUrl());
        data.put("connectTimeout", agentServiceProperties.getConnectTimeout().toString());
        data.put("readTimeout", agentServiceProperties.getReadTimeout().toString());
        data.put("maxJobsPerRecommend", jobBuddyProperties.getMaxJobsPerRecommend());
        data.put("maxResumeBytes", jobBuddyProperties.getMaxResumeBytes());
        data.put("resumeRuntimeWorkspace", jobBuddyProperties.getResumeRuntimeWorkspace());
        return data;
    }

    private Map<String, Object> serviceStatuses() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("intent", serviceStatus("intent", "Intent Service", agentServiceProperties.getIntentUrl()));
        data.put("runtime", serviceStatus("runtime", "Agent Runtime", agentServiceProperties.getRuntimeUrl()));
        data.put("memory", serviceStatus("memory", "Memory Service", agentServiceProperties.getMemoryUrl()));
        data.put("tool", serviceStatus("tool", "Tool Service", agentServiceProperties.getToolUrl()));
        data.put("eval", serviceStatus("eval", "Eval Service", agentServiceProperties.getEvalUrl()));
        return data;
    }

    private Map<String, Object> serviceStatus(String id, String name, String baseUrl) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("id", id);
        data.put("name", name);
        data.put("url", baseUrl);
        data.put("checkedAt", Instant.now().toString());
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            data.put("status", "not_configured");
            data.put("success", false);
            data.put("message", "未配置服务地址");
            return data;
        }
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(healthUrl(baseUrl)).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(HEALTH_TIMEOUT_MILLIS);
            connection.setReadTimeout(HEALTH_TIMEOUT_MILLIS);
            int code = connection.getResponseCode();
            boolean success = code >= 200 && code < 300;
            data.put("healthUrl", healthUrl(baseUrl));
            data.put("status", success ? "running" : "down");
            data.put("success", Boolean.valueOf(success));
            data.put("message", success ? "运行中" : "健康检查失败，HTTP " + code);
            connection.disconnect();
        } catch (Exception e) {
            data.put("healthUrl", healthUrl(baseUrl));
            data.put("status", "down");
            data.put("success", false);
            data.put("message", e.getMessage() == null ? "服务不可达" : e.getMessage());
        }
        return data;
    }

    private String healthUrl(String baseUrl) {
        String value = baseUrl.trim();
        if (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value + "/health";
    }

    @SuppressWarnings("unchecked")
    private void applyRuntimeSettings(Map<String, Object> settings) {
        Object workspaceValue = settings.get("workspace");
        if (workspaceValue instanceof Map) {
            Map<String, Object> workspace = (Map<String, Object>) workspaceValue;
            String defaultUserId = stringValue(workspace.get("defaultUserId"));
            if (defaultUserId != null && !defaultUserId.trim().isEmpty()) jobBuddyProperties.setDefaultUserId(defaultUserId.trim());
            Integer maxJobs = intValue(workspace.get("maxJobsPerRecommend"));
            if (maxJobs != null) {
                int normalized = clamp(maxJobs.intValue(), MIN_JOBS_PER_RECOMMEND, MAX_JOBS_PER_RECOMMEND);
                jobBuddyProperties.setMaxJobsPerRecommend(normalized);
                workspace.put("maxJobsPerRecommend", Integer.valueOf(normalized));
            }
            Integer maxResumeBytes = intValue(workspace.get("maxResumeBytes"));
            if (maxResumeBytes != null) {
                int normalized = clamp(maxResumeBytes.intValue(), MIN_RESUME_BYTES, MAX_RESUME_BYTES);
                jobBuddyProperties.setMaxResumeBytes(normalized);
                workspace.put("maxResumeBytes", Integer.valueOf(normalized));
            }
            String resumeRuntimeWorkspace = stringValue(workspace.get("resumeRuntimeWorkspace"));
            if (resumeRuntimeWorkspace != null) jobBuddyProperties.setResumeRuntimeWorkspace(resumeRuntimeWorkspace.trim());
        }
        Object servicesValue = settings.get("services");
        if (servicesValue instanceof Map) {
            Map<String, Object> services = (Map<String, Object>) servicesValue;
            setText(services, "intentUrl", new TextSetter() { public void set(String value) { agentServiceProperties.setIntentUrl(value); } });
            setText(services, "runtimeUrl", new TextSetter() { public void set(String value) { agentServiceProperties.setRuntimeUrl(value); } });
            setText(services, "memoryUrl", new TextSetter() { public void set(String value) { agentServiceProperties.setMemoryUrl(value); } });
            setText(services, "toolUrl", new TextSetter() { public void set(String value) { agentServiceProperties.setToolUrl(value); } });
            setText(services, "evalUrl", new TextSetter() { public void set(String value) { agentServiceProperties.setEvalUrl(value); } });
            Duration connectTimeout = durationValue(services.get("connectTimeout"));
            if (connectTimeout != null) agentServiceProperties.setConnectTimeout(connectTimeout);
            Duration readTimeout = durationValue(services.get("readTimeout"));
            if (readTimeout != null) agentServiceProperties.setReadTimeout(readTimeout);
        }
    }

    private Map<String, Object> getSettingsWithoutRuntime() {
        Map<String, Object> settings = getSettings();
        settings.remove("runtime");
        settings.remove("serviceStatuses");
        settings.remove("settingsPath");
        return settings;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value, Map<String, Object> fallback) {
        return value instanceof Map ? (Map<String, Object>) value : fallback;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ensureMemory(Map<String, Object> settings) {
        Object memoryValue = settings.get("memory");
        if (memoryValue instanceof Map) return (Map<String, Object>) memoryValue;
        Map<String, Object> memory = memoryDefaults();
        settings.put("memory", memory);
        return memory;
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
        item.put("id", id == null || newId ? "mem_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) : String.valueOf(id));
        item.put("type", payload.get("type") == null ? "preference" : String.valueOf(payload.get("type")));
        item.put("content", payload.get("content") == null ? "" : String.valueOf(payload.get("content")));
        item.put("source", payload.get("source") == null ? "manual" : String.valueOf(payload.get("source")));
        item.put("enabled", Boolean.valueOf(booleanValue(payload.get("enabled"), true)));
        item.put("createdAt", payload.get("createdAt") == null ? Instant.now().toString() : String.valueOf(payload.get("createdAt")));
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

    private Map<String, Object> findSameMemory(List<Map<String, Object>> items, Map<String, Object> target) {
        if (items == null || target == null) return null;
        String targetKey = memoryKey(target);
        if (targetKey.isEmpty()) return null;
        for (Map<String, Object> item : items) if (targetKey.equals(memoryKey(item))) return item;
        return null;
    }

    private void mergeMemory(Map<String, Object> existing, Map<String, Object> incoming) {
        if (existing == null || incoming == null) return;
        existing.put("content", incoming.get("content") == null ? existing.get("content") : incoming.get("content"));
        existing.put("type", incoming.get("type") == null ? existing.get("type") : incoming.get("type"));
        existing.put("source", incoming.get("source") == null ? existing.get("source") : incoming.get("source"));
        existing.put("enabled", Boolean.valueOf(booleanValue(incoming.get("enabled"), booleanValue(existing.get("enabled"), true))));
        if (existing.get("createdAt") == null && incoming.get("createdAt") != null) existing.put("createdAt", incoming.get("createdAt"));
        existing.put("updatedAt", Instant.now().toString());
    }

    private void moveMemoryToTop(List<Map<String, Object>> items, Map<String, Object> item) {
        if (items == null || item == null) return;
        if (items.remove(item)) items.add(0, item);
    }

    private String memoryKey(Map<String, Object> item) {
        if (item == null) return "";
        String type = normalizeMemoryText(String.valueOf(item.get("type") == null ? "preference" : item.get("type")));
        String content = normalizeMemoryText(String.valueOf(item.get("content") == null ? "" : item.get("content")));
        if (content.isEmpty()) return "";
        return type + "|" + content;
    }

    private String normalizeMemoryText(String value) {
        if (value == null) return "";
        return value.toLowerCase()
                .replaceAll("[\\s　]+", "")
                .replace('，', ',')
                .replace('。', '.')
                .replace('；', ';')
                .replace('：', ':')
                .trim();
    }

    private MemoryScore scoreMemory(List<String> queryTokens, String normalizedQuery, String content) {
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
        java.util.regex.Matcher ascii = java.util.regex.Pattern.compile("[a-z0-9_+#.-]{2,}").matcher(text);
        while (ascii.find()) addToken(tokens, ascii.group());
        java.util.regex.Matcher chinese = java.util.regex.Pattern.compile("[\\u4e00-\\u9fff]{2,}").matcher(text);
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
        String memoryType = type == null ? "conversation" : type.trim().toLowerCase();
        if ("conversation".equals(memoryType) || "chat".equals(memoryType)) return false;
        String text = content == null ? "" : content.trim();
        if (text.length() < 8) return false;
        String normalized = normalizeMemoryText(text);
        return normalized.contains("偏好")
                || normalized.contains("优先")
                || normalized.contains("排除")
                || normalized.contains("不要")
                || normalized.contains("不考虑")
                || normalized.contains("目标")
                || normalized.contains("期望")
                || normalized.contains("约束");
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
        if (!Files.exists(settingsPath)) return new LinkedHashMap<String, Object>();
        try {
            return objectMapper.readValue(Files.readAllBytes(settingsPath), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<String, Object>();
        }
    }

    private Map<String, Object> sanitize(Map<String, Object> payload) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (payload == null) return result;
        copyIfMap(payload, result, "workspace");
        copySanitizedServices(payload, result);
        copyIfMap(payload, result, "memory");
        copyIfMap(payload, result, "blacklist");
        return result;
    }

    private void writeSettings(Map<String, Object> settings) {
        try {
            Files.createDirectories(settingsPath.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(settingsPath.toFile(), settings);
        } catch (IOException e) {
            throw new RuntimeException("保存平台设置失败: " + e.getMessage(), e);
        }
    }

    private void copyIfMap(Map<String, Object> from, Map<String, Object> to, String key) {
        Object value = from.get(key);
        if (value instanceof Map) to.put(key, value);
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
            while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
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
            if (existing instanceof Map && incoming instanceof Map) deepMerge((Map<String, Object>) existing, (Map<String, Object>) incoming);
            else target.put(entry.getKey(), incoming);
        }
    }

    private void setText(Map<String, Object> map, String key, TextSetter setter) {
        String value = stringValue(map.get(key));
        if (value != null) setter.set(value.trim());
    }

    private interface TextSetter { void set(String value); }

    private String stringValue(Object value) { return value == null ? null : String.valueOf(value); }

    private Integer intValue(Object value) {
        if (value instanceof Number) return Integer.valueOf(((Number) value).intValue());
        if (value == null) return null;
        try { return Integer.valueOf(String.valueOf(value)); } catch (NumberFormatException e) { return null; }
    }

    private int clamp(int value, int min, int max) { return Math.min(max, Math.max(min, value)); }

    private Duration durationValue(Object value) {
        if (value instanceof Duration) return (Duration) value;
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return null;
        try {
            if (text.startsWith("PT")) return Duration.parse(text);
            if (text.endsWith("ms")) return Duration.ofMillis(Long.parseLong(text.substring(0, text.length() - 2)));
            if (text.endsWith("s")) return Duration.ofSeconds(Long.parseLong(text.substring(0, text.length() - 1)));
            if (text.endsWith("m")) return Duration.ofMinutes(Long.parseLong(text.substring(0, text.length() - 1)));
            return Duration.ofSeconds(Long.parseLong(text));
        } catch (Exception e) {
            return null;
        }
    }

}
