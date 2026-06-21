package com.jobbuddy.backend.modules.prompt.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PersonalContext {
    private final String taskType;
    private final Map<String, Object> profile;
    private final Map<String, Object> resume;
    private final List<Map<String, Object>> currentJobs;
    private final List<Map<String, Object>> favoriteJobs;
    private final List<Map<String, Object>> journeyRecords;
    private final List<Map<String, Object>> blacklistItems;
    private final List<Map<String, Object>> longTermMemory;
    private final String summary;

    public PersonalContext(String taskType,
                           Map<String, Object> profile,
                           Map<String, Object> resume,
                           List<Map<String, Object>> currentJobs,
                           List<Map<String, Object>> favoriteJobs,
                           List<Map<String, Object>> journeyRecords,
                           List<Map<String, Object>> blacklistItems,
                           List<Map<String, Object>> longTermMemory,
                           String summary) {
        this.taskType = taskType == null ? "general" : taskType;
        this.profile = safeMap(profile);
        this.resume = safeMap(resume);
        this.currentJobs = safeList(currentJobs);
        this.favoriteJobs = safeList(favoriteJobs);
        this.journeyRecords = safeList(journeyRecords);
        this.blacklistItems = safeList(blacklistItems);
        this.longTermMemory = safeList(longTermMemory);
        this.summary = summary == null ? "" : summary;
    }

    public String getTaskType() { return taskType; }
    public Map<String, Object> getProfile() { return profile; }
    public Map<String, Object> getResume() { return resume; }
    public List<Map<String, Object>> getCurrentJobs() { return currentJobs; }
    public List<Map<String, Object>> getFavoriteJobs() { return favoriteJobs; }
    public List<Map<String, Object>> getJourneyRecords() { return journeyRecords; }
    public List<Map<String, Object>> getBlacklistItems() { return blacklistItems; }
    public List<Map<String, Object>> getLongTermMemory() { return longTermMemory; }
    public String getSummary() { return summary; }

    public boolean isEmpty() {
        return profile.isEmpty() && resume.isEmpty() && currentJobs.isEmpty() && favoriteJobs.isEmpty()
                && journeyRecords.isEmpty() && longTermMemory.isEmpty() && summary.trim().isEmpty();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("task_type", taskType);
        map.put("summary", summary);
        map.put("profile_summary", profile);
        map.put("resume_summary", resume);
        map.put("current_jobs", currentJobs);
        map.put("favorite_jobs", favoriteJobs);
        map.put("journey_records", journeyRecords);
        map.put("blacklist_items", blacklistItems);
        map.put("long_term_memory", longTermMemory);
        map.put("sources", sources());
        return map;
    }

    public List<String> sources() {
        List<String> sources = new java.util.ArrayList<String>();
        if (!profile.isEmpty()) sources.add("求职画像");
        if (!resume.isEmpty()) sources.add("当前简历");
        if (!currentJobs.isEmpty()) sources.add("当前岗位列表");
        if (!favoriteJobs.isEmpty()) sources.add("收藏岗位");
        if (!journeyRecords.isEmpty()) sources.add("求职进展");
        if (!blacklistItems.isEmpty()) sources.add("黑名单/偏好");
        if (!longTermMemory.isEmpty()) sources.add("长期记忆");
        return sources;
    }

    private Map<String, Object> safeMap(Map<String, Object> value) {
        return value == null ? Collections.<String, Object>emptyMap() : new LinkedHashMap<String, Object>(value);
    }

    private List<Map<String, Object>> safeList(List<Map<String, Object>> value) {
        return value == null ? Collections.<Map<String, Object>>emptyList() : new java.util.ArrayList<Map<String, Object>>(value);
    }
}
