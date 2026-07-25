package com.jobbuddy.backend.modules.prompt.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 注入 Prompt 的个人上下文快照，集合在构造时转为只读副本。
 */
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

  /**
   * 创建个人上下文实例。
   *
   * @param taskType 任务类型
   * @param profile 画像
   * @param resume 简历
   * @param currentJobs 当前岗位列表
   * @param favoriteJobs 收藏岗位岗位列表
   * @param journeyRecords 求职旅程记录列表
   * @param blacklistItems 黑名单数据项列表
   * @param longTermMemory 长期记忆
   * @param summary 摘要
   */
  public PersonalContext(
      String taskType,
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

  /**
   * 获取任务类型。
   *
   * @return 任务类型
   */
  public String getTaskType() {
    return taskType;
  }

  /**
   * 获取画像。
   *
   * @return 画像
   */
  public Map<String, Object> getProfile() {
    return profile;
  }

  /**
   * 获取简历。
   *
   * @return 简历
   */
  public Map<String, Object> getResume() {
    return resume;
  }

  /**
   * 获取当前岗位列表。
   *
   * @return 当前岗位列表
   */
  public List<Map<String, Object>> getCurrentJobs() {
    return currentJobs;
  }

  /**
   * 获取收藏岗位列表。
   *
   * @return 收藏岗位列表
   */
  public List<Map<String, Object>> getFavoriteJobs() {
    return favoriteJobs;
  }

  /**
   * 获取求职旅程记录列表。
   *
   * @return 求职旅程记录列表
   */
  public List<Map<String, Object>> getJourneyRecords() {
    return journeyRecords;
  }

  /**
   * 获取黑名单数据项列表。
   *
   * @return 黑名单数据项列表
   */
  public List<Map<String, Object>> getBlacklistItems() {
    return blacklistItems;
  }

  /**
   * 获取长期长期记忆。
   *
   * @return 长期长期记忆
   */
  public List<Map<String, Object>> getLongTermMemory() {
    return longTermMemory;
  }

  /**
   * 获取摘要。
   *
   * @return 摘要
   */
  public String getSummary() {
    return summary;
  }

  /**
   * 判断是否空值。
   *
   * @return 是否未包含个人上下文
   */
  public boolean isEmpty() {
    return profile.isEmpty()
        && resume.isEmpty()
        && currentJobs.isEmpty()
        && favoriteJobs.isEmpty()
        && journeyRecords.isEmpty()
        && longTermMemory.isEmpty()
        && summary.trim().isEmpty();
  }

  /**
   * 将输入转换为键值映射。
   *
   * @return 键值映射
   */
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

  /**
   * 读取上下文来源列表。
   *
   * @return 上下文来源列表
   */
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

  /**
   * 将输入安全转换为映射。
   *
   * @param value 待处理值
   * @return 键值映射
   */
  private Map<String, Object> safeMap(Map<String, Object> value) {
    return value == null
        ? Collections.<String, Object>emptyMap()
        : new LinkedHashMap<String, Object>(value);
  }

  /**
   * 将输入安全转换为列表。
   *
   * @param value 待处理值
   * @return 数据列表
   */
  private List<Map<String, Object>> safeList(List<Map<String, Object>> value) {
    return value == null
        ? Collections.<Map<String, Object>>emptyList()
        : new java.util.ArrayList<Map<String, Object>>(value);
  }
}
