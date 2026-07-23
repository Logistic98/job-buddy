package com.jobbuddy.backend.modules.system.service.impl;

import com.jobbuddy.backend.modules.system.mapper.SystemSettingsMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Loads and evaluates company and keyword exclusions once per job batch. */
public class JobBlacklistPolicy {
  private final SystemSettingsMapper mapper;

  public JobBlacklistPolicy(SystemSettingsMapper mapper) {
    this.mapper = mapper;
  }

  public Map<String, Object> defaults() {
    Map<String, Object> data = new LinkedHashMap<String, Object>();
    data.put("enabled", true);
    data.put("matchMode", "contains");
    data.put("items", databaseItems());
    return data;
  }

  public void applyItems(Map<String, Object> settings, Map<String, Object> savedSettings) {
    Map<String, Object> blacklist = map(settings.get("blacklist"), defaults());
    List<Map<String, Object>> items = databaseItems();
    mergeManualItems(items, savedSettings);
    blacklist.put("items", items);
    settings.put("blacklist", blacklist);
  }

  public List<Map<String, Object>> listItems(Map<String, Object> settings) {
    Map<String, Object> blacklist = map(settings.get("blacklist"), defaults());
    return itemsFromBlacklist(blacklist);
  }

  private List<Map<String, Object>> itemsFromBlacklist(Map<String, Object> blacklist) {
    List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
    Object items = blacklist.get("items");
    if (!(items instanceof List)) return result;
    for (Object item : (List<?>) items) {
      if (item instanceof Map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) item;
        result.add(new LinkedHashMap<String, Object>(row));
      }
    }
    return result;
  }

  public boolean isBlacklisted(Map<String, Object> job, Map<String, Object> savedSettings) {
    return matches(job, snapshot(savedSettings));
  }

  public List<Map<String, Object>> filter(
      List<Map<String, Object>> jobs, Map<String, Object> savedSettings) {
    List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
    if (jobs == null) return result;
    Map<String, Object> blacklist = snapshot(savedSettings);
    for (Map<String, Object> job : jobs) if (!matches(job, blacklist)) result.add(job);
    return result;
  }

  private List<Map<String, Object>> databaseItems() {
    try {
      List<Map<String, Object>> rows = mapper.listBlacklistItems();
      for (Map<String, Object> item : rows) {
        Object createdAt = item.get("createdAt");
        if (createdAt instanceof java.sql.Timestamp) {
          item.put("createdAt", ((java.sql.Timestamp) createdAt).toInstant().toString());
        }
      }
      return rows;
    } catch (Exception exception) {
      return new ArrayList<Map<String, Object>>();
    }
  }

  @SuppressWarnings("unchecked")
  private void mergeManualItems(
      List<Map<String, Object>> result, Map<String, Object> savedSettings) {
    Object savedBlacklist = savedSettings == null ? null : savedSettings.get("blacklist");
    if (!(savedBlacklist instanceof Map)) return;
    Object savedItems = ((Map<String, Object>) savedBlacklist).get("items");
    if (!(savedItems instanceof List)) return;
    Map<String, Map<String, Object>> byKey = new LinkedHashMap<String, Map<String, Object>>();
    for (Map<String, Object> item : result) byKey.put(key(item), item);
    for (Object item : (List<?>) savedItems) {
      if (!(item instanceof Map)) continue;
      Map<String, Object> row = new LinkedHashMap<String, Object>((Map<String, Object>) item);
      String key = key(row);
      Map<String, Object> existing = byKey.get(key);
      if (existing != null) {
        if (row.containsKey("enabled")) existing.put("enabled", row.get("enabled"));
        if (row.containsKey("reason")) existing.put("reason", row.get("reason"));
      } else {
        result.add(row);
        byKey.put(key, row);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> snapshot(Map<String, Object> savedSettings) {
    Map<String, Object> blacklist = new LinkedHashMap<String, Object>();
    blacklist.put("enabled", true);
    blacklist.put("matchMode", "contains");
    Object savedBlacklist = savedSettings == null ? null : savedSettings.get("blacklist");
    if (savedBlacklist instanceof Map) {
      Map<String, Object> saved = (Map<String, Object>) savedBlacklist;
      if (saved.containsKey("enabled")) blacklist.put("enabled", saved.get("enabled"));
      if (saved.containsKey("matchMode")) blacklist.put("matchMode", saved.get("matchMode"));
    }
    List<Map<String, Object>> items = databaseItems();
    mergeManualItems(items, savedSettings);
    blacklist.put("items", items);
    return blacklist;
  }

  private boolean matches(Map<String, Object> job, Map<String, Object> blacklist) {
    if (!booleanValue(blacklist.get("enabled"), true) || job == null) return false;
    String companyText =
        fields(job, "brandName", "companyName", "company", "companyShortName", "brandFullName");
    String contentText =
        fields(
            job,
            "jobName",
            "job_name",
            "title",
            "name",
            "jobDescription",
            "description",
            "postDescription",
            "jobDesc",
            "jobSecText",
            "detailText",
            "jobRequire",
            "skills",
            "jobLabels",
            "labels",
            "welfareList",
            "welfare",
            "benefits");
    for (Map<String, Object> item : itemsFromBlacklist(blacklist)) {
      if (!booleanValue(item.get("enabled"), true)) continue;
      String name = normalized(item.get("name"));
      String type = normalized(item.get("type"));
      if (name.isEmpty()) continue;
      if ("company".equals(type) && companyText.contains(name)) return true;
      if ("keyword".equals(type) && keywordMatches(contentText, name)) return true;
    }
    return false;
  }

  private boolean keywordMatches(String content, String keyword) {
    if (!keyword.matches("[a-z0-9]+")) return content.contains(keyword);
    return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(keyword) + "(?![a-z0-9])")
        .matcher(content)
        .find();
  }

  private String fields(Map<String, Object> job, String... keys) {
    StringBuilder text = new StringBuilder();
    for (String key : keys) {
      Object value = job.get(key);
      if (value == null) continue;
      if (text.length() > 0) text.append(' ');
      text.append(value);
    }
    return text.toString().toLowerCase(Locale.ROOT);
  }

  private String normalized(Object value) {
    return value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
  }

  private String key(Map<String, Object> item) {
    return String.valueOf(item.get("name")) + "#" + String.valueOf(item.get("type"));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> map(Object value, Map<String, Object> fallback) {
    return value instanceof Map ? (Map<String, Object>) value : fallback;
  }

  private boolean booleanValue(Object value, boolean fallback) {
    if (value instanceof Boolean) return ((Boolean) value).booleanValue();
    if (value == null) return fallback;
    String text = String.valueOf(value);
    return "true".equalsIgnoreCase(text) || "1".equals(text);
  }
}
