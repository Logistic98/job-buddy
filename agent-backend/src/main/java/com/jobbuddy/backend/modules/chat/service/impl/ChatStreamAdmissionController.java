package com.jobbuddy.backend.modules.chat.service.impl;

import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.modules.chat.exception.ChatStreamRejectedException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Atomic global, tenant, and user admission accounting for long-lived SSE work. */
@Component
public class ChatStreamAdmissionController {
  private final int globalLimit;
  private final int tenantLimit;
  private final int userLimit;
  private final Map<String, Integer> tenantCounts = new HashMap<String, Integer>();
  private final Map<String, Integer> userCounts = new HashMap<String, Integer>();
  private int globalCount;

  @Autowired
  public ChatStreamAdmissionController(AgentServiceProperties properties) {
    this(
        properties.getStreamMaxGlobal(),
        properties.getStreamMaxPerTenant(),
        properties.getStreamMaxPerUser());
  }

  ChatStreamAdmissionController(int globalLimit, int tenantLimit, int userLimit) {
    this.globalLimit = Math.max(1, globalLimit);
    this.tenantLimit = Math.max(1, tenantLimit);
    this.userLimit = Math.max(1, userLimit);
  }

  public synchronized Lease acquire(String tenantId, String userId) {
    String tenant = required(tenantId, "租户");
    String user = required(userId, "用户");
    String userKey = tenant + "\n" + user;
    int tenantCount = tenantCounts.getOrDefault(tenant, 0);
    int userCount = userCounts.getOrDefault(userKey, 0);
    if (globalCount >= globalLimit || tenantCount >= tenantLimit || userCount >= userLimit) {
      throw new ChatStreamRejectedException("当前流式任务较多，请稍后重试", true);
    }
    globalCount++;
    tenantCounts.put(tenant, tenantCount + 1);
    userCounts.put(userKey, userCount + 1);
    return new Lease(this, tenant, userKey);
  }

  private String required(String value, String label) {
    if (value == null || value.trim().isEmpty()) {
      throw new ChatStreamRejectedException(label + "身份缺失，无法创建流式任务", false);
    }
    return value.trim();
  }

  private synchronized void release(String tenant, String userKey) {
    globalCount = Math.max(0, globalCount - 1);
    decrement(tenantCounts, tenant);
    decrement(userCounts, userKey);
  }

  private void decrement(Map<String, Integer> counts, String key) {
    int next = counts.getOrDefault(key, 0) - 1;
    if (next <= 0) counts.remove(key);
    else counts.put(key, next);
  }

  synchronized int activeGlobal() {
    return globalCount;
  }

  public static final class Lease implements AutoCloseable {
    private final ChatStreamAdmissionController owner;
    private final String tenant;
    private final String userKey;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private Lease(ChatStreamAdmissionController owner, String tenant, String userKey) {
      this.owner = owner;
      this.tenant = tenant;
      this.userKey = userKey;
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) owner.release(tenant, userKey);
    }
  }
}
