package com.jobbuddy.backend.modules.chat.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 定义对话会话状态。
 */
public class ChatSessionState {
  /**
   * 当前 {@code getOrCreate} 调用是否新建了会话。该瞬时标记不进入 PostgreSQL 或 Redis，只用于跳过不存在的历史消息读取。
   */
  public transient boolean newlyCreated;

  public String tenantId;
  public String userId;
  public String sessionId;
  public String resumeId;
  public List<Map<String, Object>> attachments = new ArrayList<Map<String, Object>>();
  public List<Map<String, Object>> jobs = new ArrayList<Map<String, Object>>();
  public List<Map<String, Object>> toolEvents = new ArrayList<Map<String, Object>>();
  public Map<String, Object> lastSlots;
  public Map<String, Object> resumeMatch;
}
