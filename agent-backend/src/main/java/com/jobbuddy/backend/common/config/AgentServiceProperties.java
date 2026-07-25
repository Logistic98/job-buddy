package com.jobbuddy.backend.common.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 绑定内部 Agent 服务地址与公共 HTTP 超时配置。
 */
@Component
@ConfigurationProperties(prefix = "agent.services")
public class AgentServiceProperties {
  private String intentUrl;
  private String memoryUrl;
  private String toolUrl;
  private String evalUrl;
  private String runtimeUrl;
  private String sandboxUrl;
  private String internalServiceToken;
  private Duration connectTimeout = Duration.ofSeconds(2);
  private Duration readTimeout = Duration.ofSeconds(135);
  private Duration memoryConnectTimeout = Duration.ofSeconds(2);
  private Duration memoryReadTimeout = Duration.ofSeconds(10);
  private Duration streamConnectTimeout = Duration.ofSeconds(10);
  private Duration streamReadTimeout = Duration.ofSeconds(180);
  private Duration streamSessionTimeout = Duration.ofMinutes(15);
  private Duration streamHeartbeatInterval = Duration.ofSeconds(10);
  private int streamCoreThreads = 4;
  private int streamMaxThreads = 64;
  private int streamQueueCapacity = 128;
  private int streamMaxGlobal = 96;
  private int streamMaxPerTenant = 48;
  private int streamMaxPerUser = 6;
  private int maxAttempts = 2;
  private Duration retryBackoff = Duration.ofMillis(200);
  private int circuitFailureThreshold = 5;
  private Duration circuitOpenDuration = Duration.ofSeconds(15);

  /**
   * 获取意图服务 URL。
   *
   * @return 意图服务 URL
   */
  public String getIntentUrl() {
    return intentUrl;
  }

  /**
   * 设置意图服务 URL。
   *
   * @param intentUrl 意图服务 URL
   */
  public void setIntentUrl(String intentUrl) {
    this.intentUrl = intentUrl;
  }

  /**
   * 获取记忆服务 URL。
   *
   * @return 记忆服务 URL
   */
  public String getMemoryUrl() {
    return memoryUrl;
  }

  /**
   * 设置记忆服务 URL。
   *
   * @param memoryUrl 记忆服务 URL
   */
  public void setMemoryUrl(String memoryUrl) {
    this.memoryUrl = memoryUrl;
  }

  /**
   * 获取工具服务 URL。
   *
   * @return 工具服务 URL
   */
  public String getToolUrl() {
    return toolUrl;
  }

  /**
   * 设置工具服务 URL。
   *
   * @param toolUrl 工具服务 URL
   */
  public void setToolUrl(String toolUrl) {
    this.toolUrl = toolUrl;
  }

  /**
   * 获取评估服务 URL。
   *
   * @return 评估服务地址
   */
  public String getEvalUrl() {
    return evalUrl;
  }

  /**
   * 设置评估服务 URL。
   *
   * @param evalUrl 评估地址
   */
  public void setEvalUrl(String evalUrl) {
    this.evalUrl = evalUrl;
  }

  /**
   * 获取 Runtime 服务 URL。
   *
   * @return Runtime 服务 URL
   */
  public String getRuntimeUrl() {
    return runtimeUrl;
  }

  /**
   * 设置 Runtime 服务 URL。
   *
   * @param runtimeUrl Runtime 服务 URL
   */
  public void setRuntimeUrl(String runtimeUrl) {
    this.runtimeUrl = runtimeUrl;
  }

  /**
   * 获取沙箱服务 URL。
   *
   * @return 沙箱服务 URL
   */
  public String getSandboxUrl() {
    return sandboxUrl;
  }

  /**
   * 设置沙箱服务 URL。
   *
   * @param sandboxUrl 沙箱地址
   */
  public void setSandboxUrl(String sandboxUrl) {
    this.sandboxUrl = sandboxUrl;
  }

  /**
   * 获取内部服务令牌。
   *
   * @return 内部服务令牌
   */
  public String getInternalServiceToken() {
    return internalServiceToken;
  }

  /**
   * 设置内部服务令牌。
   *
   * @param internalServiceToken 内部服务令牌
   */
  public void setInternalServiceToken(String internalServiceToken) {
    this.internalServiceToken = internalServiceToken;
  }

  /**
   * 规范化的 agent-runtime 基础地址；未配置时为空，是调用方唯一地址来源。
   *
   * @return 规范化后的的 agent-runtime 基础地址；未配置时为空，是调用方唯一地址来源
   */
  public String resolvedRuntimeUrl() {
    return normalizeBaseUrl(runtimeUrl);
  }

  /**
   * 规范化的 agent-sandbox 基础地址，未配置时为空。
   *
   * @return 规范化后的的 agent-sandbox 基础地址，未配置时为空
   */
  public String resolvedSandboxUrl() {
    return normalizeBaseUrl(sandboxUrl);
  }

  /**
   * 规范化的 agent-memory 基础地址，未配置时为空。
   *
   * @return 规范化后的的 agent-memory 基础地址，未配置时为空
   */
  public String resolvedMemoryUrl() {
    return normalizeBaseUrl(memoryUrl);
  }

  /**
   * 去除首尾空白的跨服务令牌，未配置时为空。
   *
   * @return 内部服务令牌
   */
  public String resolvedInternalServiceToken() {
    return internalServiceToken == null ? "" : internalServiceToken.trim();
  }

  /**
   * 规范化基础 URL。
   *
   * @param value 待处理值
   * @return 规范化基础 URL
   */
  private static String normalizeBaseUrl(String value) {
    if (value == null) return "";
    String url = value.trim();
    if (url.isEmpty() || url.contains("${")) return "";
    while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
    return url;
  }

  /**
   * 获取连接超时。
   *
   * @return 连接超时
   */
  public Duration getConnectTimeout() {
    return connectTimeout;
  }

  /**
   * 设置连接超时。
   *
   * @param connectTimeout 连接超时
   */
  public void setConnectTimeout(Duration connectTimeout) {
    this.connectTimeout = connectTimeout;
  }

  /**
   * 获取读取超时。
   *
   * @return 读取超时
   */
  public Duration getReadTimeout() {
    return readTimeout;
  }

  /**
   * 设置读取超时。
   *
   * @param readTimeout 读取超时
   */
  public void setReadTimeout(Duration readTimeout) {
    this.readTimeout = readTimeout;
  }

  /**
   * 获取记忆连接超时。
   *
   * @return 记忆连接超时
   */
  public Duration getMemoryConnectTimeout() {
    return memoryConnectTimeout;
  }

  /**
   * 设置记忆连接超时。
   *
   * @param memoryConnectTimeout 记忆连接超时
   */
  public void setMemoryConnectTimeout(Duration memoryConnectTimeout) {
    this.memoryConnectTimeout = memoryConnectTimeout;
  }

  /**
   * 获取记忆读取超时。
   *
   * @return 记忆读取超时
   */
  public Duration getMemoryReadTimeout() {
    return memoryReadTimeout;
  }

  /**
   * 设置记忆读取超时。
   *
   * @param memoryReadTimeout 记忆读取超时
   */
  public void setMemoryReadTimeout(Duration memoryReadTimeout) {
    this.memoryReadTimeout = memoryReadTimeout;
  }

  /**
   * 获取流式响应连接超时。
   *
   * @return 流式响应连接超时
   */
  public Duration getStreamConnectTimeout() {
    return streamConnectTimeout;
  }

  /**
   * 设置流式响应连接超时。
   *
   * @param streamConnectTimeout 流式响应连接超时
   */
  public void setStreamConnectTimeout(Duration streamConnectTimeout) {
    this.streamConnectTimeout = streamConnectTimeout;
  }

  /**
   * 获取流式响应读取超时。
   *
   * @return 流式响应读取超时
   */
  public Duration getStreamReadTimeout() {
    return streamReadTimeout;
  }

  /**
   * 设置流式响应读取超时。
   *
   * @param streamReadTimeout 流式响应读取超时
   */
  public void setStreamReadTimeout(Duration streamReadTimeout) {
    this.streamReadTimeout = streamReadTimeout;
  }

  /**
   * 获取流式响应会话超时。
   *
   * @return 流式响应会话超时
   */
  public Duration getStreamSessionTimeout() {
    return streamSessionTimeout;
  }

  /**
   * 设置流式响应会话超时。
   *
   * @param streamSessionTimeout 流式响应会话超时
   */
  public void setStreamSessionTimeout(Duration streamSessionTimeout) {
    this.streamSessionTimeout = streamSessionTimeout;
  }

  /**
   * 获取流式响应心跳间隔。
   *
   * @return 流式响应心跳间隔
   */
  public Duration getStreamHeartbeatInterval() {
    return streamHeartbeatInterval;
  }

  /**
   * 设置流式响应心跳间隔。
   *
   * @param streamHeartbeatInterval 流式心跳间隔
   */
  public void setStreamHeartbeatInterval(Duration streamHeartbeatInterval) {
    this.streamHeartbeatInterval = streamHeartbeatInterval;
  }

  /**
   * 获取流式响应核心线程。
   *
   * @return 流式任务核心线程数线程数
   */
  public int getStreamCoreThreads() {
    return streamCoreThreads;
  }

  /**
   * 设置流式响应核心线程。
   *
   * @param streamCoreThreads 流式核心线程数
   */
  public void setStreamCoreThreads(int streamCoreThreads) {
    this.streamCoreThreads = streamCoreThreads;
  }

  /**
   * 获取流式响应最大线程数。
   *
   * @return 流式任务最大线程数
   */
  public int getStreamMaxThreads() {
    return streamMaxThreads;
  }

  /**
   * 设置流式响应最大线程数。
   *
   * @param streamMaxThreads 流式响应最大线程数
   */
  public void setStreamMaxThreads(int streamMaxThreads) {
    this.streamMaxThreads = streamMaxThreads;
  }

  /**
   * 获取流式响应队列容量。
   *
   * @return 流式任务队列容量
   */
  public int getStreamQueueCapacity() {
    return streamQueueCapacity;
  }

  /**
   * 设置流式响应队列容量。
   *
   * @param streamQueueCapacity 流式队列容量
   */
  public void setStreamQueueCapacity(int streamQueueCapacity) {
    this.streamQueueCapacity = streamQueueCapacity;
  }

  /**
   * 获取全局流式响应上限。
   *
   * @return 流式任务最大全局并发数
   */
  public int getStreamMaxGlobal() {
    return streamMaxGlobal;
  }

  /**
   * 设置全局流式响应上限。
   *
   * @param streamMaxGlobal 全局流式响应上限
   */
  public void setStreamMaxGlobal(int streamMaxGlobal) {
    this.streamMaxGlobal = streamMaxGlobal;
  }

  /**
   * 获取单租户流式响应上限。
   *
   * @return 流式任务最大每次租户并发数
   */
  public int getStreamMaxPerTenant() {
    return streamMaxPerTenant;
  }

  /**
   * 设置单租户流式响应上限。
   *
   * @param streamMaxPerTenant 单租户流式响应上限
   */
  public void setStreamMaxPerTenant(int streamMaxPerTenant) {
    this.streamMaxPerTenant = streamMaxPerTenant;
  }

  /**
   * 获取单用户流式响应上限。
   *
   * @return 流式任务最大每次用户并发数
   */
  public int getStreamMaxPerUser() {
    return streamMaxPerUser;
  }

  /**
   * 设置单用户流式响应上限。
   *
   * @param streamMaxPerUser 单用户流式响应上限
   */
  public void setStreamMaxPerUser(int streamMaxPerUser) {
    this.streamMaxPerUser = streamMaxPerUser;
  }

  /**
   * 获取最大尝试次数。
   *
   * @return 最大尝试次数
   */
  public int getMaxAttempts() {
    return maxAttempts;
  }

  /**
   * 设置最大尝试次数。
   *
   * @param maxAttempts 最大尝试次数
   */
  public void setMaxAttempts(int maxAttempts) {
    this.maxAttempts = maxAttempts;
  }

  /**
   * 获取重试退避时间。
   *
   * @return 重试退避时间
   */
  public Duration getRetryBackoff() {
    return retryBackoff;
  }

  /**
   * 设置重试退避时间。
   *
   * @param retryBackoff 重试退避时间
   */
  public void setRetryBackoff(Duration retryBackoff) {
    this.retryBackoff = retryBackoff;
  }

  /**
   * 获取熔断器失败阈值。
   *
   * @return 熔断失败阈值
   */
  public int getCircuitFailureThreshold() {
    return circuitFailureThreshold;
  }

  /**
   * 设置熔断器失败阈值。
   *
   * @param circuitFailureThreshold 熔断失败阈值
   */
  public void setCircuitFailureThreshold(int circuitFailureThreshold) {
    this.circuitFailureThreshold = circuitFailureThreshold;
  }

  /**
   * 获取熔断器打开时长。
   *
   * @return 熔断器打开时长
   */
  public Duration getCircuitOpenDuration() {
    return circuitOpenDuration;
  }

  /**
   * 设置熔断器打开时长。
   *
   * @param circuitOpenDuration 熔断开启时长
   */
  public void setCircuitOpenDuration(Duration circuitOpenDuration) {
    this.circuitOpenDuration = circuitOpenDuration;
  }
}
