package com.jobbuddy.backend.common.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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
  private Duration readTimeout = Duration.ofSeconds(75);
  private Duration streamConnectTimeout = Duration.ofSeconds(10);
  private Duration streamReadTimeout = Duration.ofSeconds(180);
  private int maxAttempts = 2;
  private Duration retryBackoff = Duration.ofMillis(200);
  private int circuitFailureThreshold = 5;
  private Duration circuitOpenDuration = Duration.ofSeconds(15);

  public String getIntentUrl() {
    return intentUrl;
  }

  public void setIntentUrl(String intentUrl) {
    this.intentUrl = intentUrl;
  }

  public String getMemoryUrl() {
    return memoryUrl;
  }

  public void setMemoryUrl(String memoryUrl) {
    this.memoryUrl = memoryUrl;
  }

  public String getToolUrl() {
    return toolUrl;
  }

  public void setToolUrl(String toolUrl) {
    this.toolUrl = toolUrl;
  }

  public String getEvalUrl() {
    return evalUrl;
  }

  public void setEvalUrl(String evalUrl) {
    this.evalUrl = evalUrl;
  }

  public String getRuntimeUrl() {
    return runtimeUrl;
  }

  public void setRuntimeUrl(String runtimeUrl) {
    this.runtimeUrl = runtimeUrl;
  }

  public String getSandboxUrl() {
    return sandboxUrl;
  }

  public void setSandboxUrl(String sandboxUrl) {
    this.sandboxUrl = sandboxUrl;
  }

  public String getInternalServiceToken() {
    return internalServiceToken;
  }

  public void setInternalServiceToken(String internalServiceToken) {
    this.internalServiceToken = internalServiceToken;
  }

  /**
   * Normalized agent-runtime base URL ("" when unconfigured); single source of truth for callers.
   */
  public String resolvedRuntimeUrl() {
    return normalizeBaseUrl(runtimeUrl);
  }

  /** Normalized agent-sandbox base URL ("" when unconfigured). */
  public String resolvedSandboxUrl() {
    return normalizeBaseUrl(sandboxUrl);
  }

  /** Trimmed cross-service token ("" when unconfigured). */
  public String resolvedInternalServiceToken() {
    return internalServiceToken == null ? "" : internalServiceToken.trim();
  }

  private static String normalizeBaseUrl(String value) {
    if (value == null) return "";
    String url = value.trim();
    if (url.isEmpty() || url.contains("${")) return "";
    while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
    return url;
  }

  public Duration getConnectTimeout() {
    return connectTimeout;
  }

  public void setConnectTimeout(Duration connectTimeout) {
    this.connectTimeout = connectTimeout;
  }

  public Duration getReadTimeout() {
    return readTimeout;
  }

  public void setReadTimeout(Duration readTimeout) {
    this.readTimeout = readTimeout;
  }

  public Duration getStreamConnectTimeout() {
    return streamConnectTimeout;
  }

  public void setStreamConnectTimeout(Duration streamConnectTimeout) {
    this.streamConnectTimeout = streamConnectTimeout;
  }

  public Duration getStreamReadTimeout() {
    return streamReadTimeout;
  }

  public void setStreamReadTimeout(Duration streamReadTimeout) {
    this.streamReadTimeout = streamReadTimeout;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public void setMaxAttempts(int maxAttempts) {
    this.maxAttempts = maxAttempts;
  }

  public Duration getRetryBackoff() {
    return retryBackoff;
  }

  public void setRetryBackoff(Duration retryBackoff) {
    this.retryBackoff = retryBackoff;
  }

  public int getCircuitFailureThreshold() {
    return circuitFailureThreshold;
  }

  public void setCircuitFailureThreshold(int circuitFailureThreshold) {
    this.circuitFailureThreshold = circuitFailureThreshold;
  }

  public Duration getCircuitOpenDuration() {
    return circuitOpenDuration;
  }

  public void setCircuitOpenDuration(Duration circuitOpenDuration) {
    this.circuitOpenDuration = circuitOpenDuration;
  }
}
