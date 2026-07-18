package com.jobbuddy.backend.modules.chat.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ChatSessionState {
  public String tenantId;
  public String userId;
  public String sessionId;
  public String resumeId;
  public List<Map<String, Object>> jobs = new ArrayList<Map<String, Object>>();
  public List<Map<String, Object>> toolEvents = new ArrayList<Map<String, Object>>();
  public Map<String, Object> lastSlots;
  public Map<String, Object> resumeMatch;
}
