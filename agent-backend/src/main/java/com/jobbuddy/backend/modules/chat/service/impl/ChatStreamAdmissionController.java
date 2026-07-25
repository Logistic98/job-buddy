package com.jobbuddy.backend.modules.chat.service.impl;

import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.modules.chat.exception.ChatStreamRejectedException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 对长连接 SSE 执行全局、租户和用户三级原子准入计数。
 */
@Component
public class ChatStreamAdmissionController {
  private final int globalLimit;
  private final int tenantLimit;
  private final int userLimit;
  private final Map<String, Integer> tenantCounts = new HashMap<String, Integer>();
  private final Map<String, Integer> userCounts = new HashMap<String, Integer>();
  private int globalCount;

  /**
   * 创建对话流式响应准入接口实例。
   *
   * @param properties 配置属性
   */
  @Autowired
  public ChatStreamAdmissionController(AgentServiceProperties properties) {
    this(
        properties.getStreamMaxGlobal(),
        properties.getStreamMaxPerTenant(),
        properties.getStreamMaxPerUser());
  }

  /**
   * 创建对话流式响应准入接口实例。
   *
   * @param globalLimit 全局上限
   * @param tenantLimit 租户上限
   * @param userLimit 用户上限
   */
  ChatStreamAdmissionController(int globalLimit, int tenantLimit, int userLimit) {
    this.globalLimit = Math.max(1, globalLimit);
    this.tenantLimit = Math.max(1, tenantLimit);
    this.userLimit = Math.max(1, userLimit);
  }

  /**
   * 申请流式响应执行许可。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 并发许可
   */
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

  /**
   * 校验并获取必填值。
   *
   * @param value 输入值
   * @param label 展示标签
   * @return 必填配置值
   */
  private String required(String value, String label) {
    if (value == null || value.trim().isEmpty()) {
      throw new ChatStreamRejectedException(label + "身份缺失，无法创建流式任务", false);
    }
    return value.trim();
  }

  /**
   * 释放流式响应执行许可。
   *
   * @param tenant 租户
   * @param userKey 用户键
   */
  private synchronized void release(String tenant, String userKey) {
    globalCount = Math.max(0, globalCount - 1);
    decrement(tenantCounts, tenant);
    decrement(userCounts, userKey);
  }

  /**
   * 减少活动任务计数。
   *
   * @param counts 分类计数
   * @param key 业务键
   */
  private void decrement(Map<String, Integer> counts, String key) {
    int next = counts.getOrDefault(key, 0) - 1;
    if (next <= 0) counts.remove(key);
    else counts.put(key, next);
  }

  /**
   * 获取当前启用的全局数据。
   *
   * @return 当前启用的全局数据
   */
  synchronized int activeGlobal() {
    return globalCount;
  }

  /**
   * 定义许可凭证。
   */
  public static final class Lease implements AutoCloseable {
    private final ChatStreamAdmissionController owner;
    private final String tenant;
    private final String userKey;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 创建许可凭证实例。
     *
     * @param owner 属主
     * @param tenant 租户
     * @param userKey 用户键
     */
    private Lease(ChatStreamAdmissionController owner, String tenant, String userKey) {
      this.owner = owner;
      this.tenant = tenant;
      this.userKey = userKey;
    }

    /**
     * 关闭当前资源。
     */
    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) owner.release(tenant, userKey);
    }
  }
}
