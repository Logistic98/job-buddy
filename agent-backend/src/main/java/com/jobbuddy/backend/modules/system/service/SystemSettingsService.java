package com.jobbuddy.backend.modules.system.service;

import java.util.List;
import java.util.Map;

public interface SystemSettingsService {
    Map<String, Object> getSettings();
    Map<String, Object> saveSettings(Map<String, Object> payload);
    List<Map<String, Object>> listMemories();
    Map<String, Object> addMemory(Map<String, Object> payload);
    void writeLocalMemory(String type, String content, String source);
    void deleteMemory(String memoryId);
    int clearMemories();
    List<Map<String, Object>> searchLocalMemories(String query, int limit);
    List<Map<String, Object>> listBlacklistItems();
    boolean isBlacklistedJob(Map<String, Object> job);
    List<Map<String, Object>> filterBlacklistedJobs(List<Map<String, Object>> jobs);
}
