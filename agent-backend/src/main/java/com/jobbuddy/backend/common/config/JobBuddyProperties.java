package com.jobbuddy.backend.common.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Centralized configuration bound from {@code job-buddy.*} properties. */
@Component
@ConfigurationProperties(prefix = "job-buddy")
public class JobBuddyProperties {

  private String environment = "development";
  private String defaultUserId = "default-user";
  private List<String> corsAllowedOrigins =
      new ArrayList<String>(List.of("http://localhost:5173", "http://127.0.0.1:5173"));

  /** Job recommendation and scoring safeguards. */
  // 平台设置会在进程运行期间更新这些字段；volatile 保证 SSE 和候选池工作线程立即可见。
  private volatile int maxJobsPerRecommend = 15;

  private volatile int recommendOverfetchFactor = 5;
  private volatile int maxJobsPerScoring = 80;
  private volatile int minimumRecommendedMatchScore = 60;

  /** Boss live-search throttling and cache settings. */
  private volatile int bossSearchMaxPages = 2;

  private volatile int bossSearchTargetCandidates = 45;
  private volatile int bossSearchMaxPageDepth = 5;
  private volatile int bossSearchPageDelayMillis = 5000;
  private volatile int bossSearchCacheTtlMinutes = 30;
  private volatile int bossSearchCooldownMinutesOnRisk = 30;
  private String bossWebBaseUrl = "https://www.zhipin.com";

  /**
   * 高风险意图安全门控开关。开启后，agent-intent 预判为高风险且建议拒绝的请求会被独立拦截， 不再进入 runtime
   * 链路。属于用户可见行为变更，默认关闭，需完成浏览器端到端验证后再开启。
   */
  private boolean intentSafetyGateEnabled = false;

  /** Runtime budget limits used when delegating Agent execution. */
  private volatile int runtimeMaxTurns = 12;

  private volatile int runtimeMaxToolCalls = 20;
  private volatile int runtimeMaxFailures = 3;
  private int runtimeMaxTokens = 32768;

  /** Resume upload and object-storage configuration. */
  private volatile int maxResumeBytes = 5 * 1024 * 1024;

  private String resumeRuntimeWorkspace = "";

  /** 简历撰写版本历史:每个用户保留的最大版本数与单版本快照大小上限。 */
  private volatile int resumeWriterVersionLimit = 30;

  private int resumeWriterSnapshotMaxBytes = 2 * 1024 * 1024;
  private Auth auth = new Auth();
  private Minio minio = new Minio();

  public String getEnvironment() {
    return environment;
  }

  public void setEnvironment(String environment) {
    this.environment = environment;
  }

  public String getDefaultUserId() {
    return defaultUserId;
  }

  public int getResumeWriterVersionLimit() {
    return resumeWriterVersionLimit;
  }

  public void setResumeWriterVersionLimit(int resumeWriterVersionLimit) {
    this.resumeWriterVersionLimit = resumeWriterVersionLimit;
  }

  public int getResumeWriterSnapshotMaxBytes() {
    return resumeWriterSnapshotMaxBytes;
  }

  public void setResumeWriterSnapshotMaxBytes(int resumeWriterSnapshotMaxBytes) {
    this.resumeWriterSnapshotMaxBytes = resumeWriterSnapshotMaxBytes;
  }

  public void setDefaultUserId(String defaultUserId) {
    this.defaultUserId = defaultUserId;
  }

  public List<String> getCorsAllowedOrigins() {
    return corsAllowedOrigins;
  }

  public void setCorsAllowedOrigins(List<String> corsAllowedOrigins) {
    this.corsAllowedOrigins =
        corsAllowedOrigins == null
            ? new ArrayList<String>()
            : new ArrayList<String>(corsAllowedOrigins);
  }

  public int getMaxJobsPerRecommend() {
    return maxJobsPerRecommend;
  }

  public void setMaxJobsPerRecommend(int maxJobsPerRecommend) {
    this.maxJobsPerRecommend = maxJobsPerRecommend;
  }

  public int getRecommendOverfetchFactor() {
    return recommendOverfetchFactor;
  }

  public void setRecommendOverfetchFactor(int recommendOverfetchFactor) {
    this.recommendOverfetchFactor = recommendOverfetchFactor;
  }

  public int getMaxJobsPerScoring() {
    return maxJobsPerScoring;
  }

  public void setMaxJobsPerScoring(int maxJobsPerScoring) {
    this.maxJobsPerScoring = maxJobsPerScoring;
  }

  public int getMinimumRecommendedMatchScore() {
    return minimumRecommendedMatchScore;
  }

  public void setMinimumRecommendedMatchScore(int minimumRecommendedMatchScore) {
    this.minimumRecommendedMatchScore = minimumRecommendedMatchScore;
  }

  public int getBossSearchMaxPages() {
    return bossSearchMaxPages;
  }

  public void setBossSearchMaxPages(int bossSearchMaxPages) {
    this.bossSearchMaxPages = bossSearchMaxPages;
  }

  public int getBossSearchTargetCandidates() {
    return bossSearchTargetCandidates;
  }

  public void setBossSearchTargetCandidates(int bossSearchTargetCandidates) {
    this.bossSearchTargetCandidates = bossSearchTargetCandidates;
  }

  public int getBossSearchMaxPageDepth() {
    return bossSearchMaxPageDepth;
  }

  public void setBossSearchMaxPageDepth(int bossSearchMaxPageDepth) {
    this.bossSearchMaxPageDepth = bossSearchMaxPageDepth;
  }

  public int getBossSearchPageDelayMillis() {
    return bossSearchPageDelayMillis;
  }

  public void setBossSearchPageDelayMillis(int bossSearchPageDelayMillis) {
    this.bossSearchPageDelayMillis = bossSearchPageDelayMillis;
  }

  public int getBossSearchCacheTtlMinutes() {
    return bossSearchCacheTtlMinutes;
  }

  public void setBossSearchCacheTtlMinutes(int bossSearchCacheTtlMinutes) {
    this.bossSearchCacheTtlMinutes = bossSearchCacheTtlMinutes;
  }

  public int getBossSearchCooldownMinutesOnRisk() {
    return bossSearchCooldownMinutesOnRisk;
  }

  public void setBossSearchCooldownMinutesOnRisk(int bossSearchCooldownMinutesOnRisk) {
    this.bossSearchCooldownMinutesOnRisk = bossSearchCooldownMinutesOnRisk;
  }

  public String getBossWebBaseUrl() {
    return bossWebBaseUrl;
  }

  public void setBossWebBaseUrl(String bossWebBaseUrl) {
    this.bossWebBaseUrl = bossWebBaseUrl;
  }

  public boolean isIntentSafetyGateEnabled() {
    return intentSafetyGateEnabled;
  }

  public void setIntentSafetyGateEnabled(boolean intentSafetyGateEnabled) {
    this.intentSafetyGateEnabled = intentSafetyGateEnabled;
  }

  public int getRuntimeMaxTurns() {
    return runtimeMaxTurns;
  }

  public void setRuntimeMaxTurns(int runtimeMaxTurns) {
    this.runtimeMaxTurns = runtimeMaxTurns;
  }

  public int getRuntimeMaxToolCalls() {
    return runtimeMaxToolCalls;
  }

  public void setRuntimeMaxToolCalls(int runtimeMaxToolCalls) {
    this.runtimeMaxToolCalls = runtimeMaxToolCalls;
  }

  public int getRuntimeMaxFailures() {
    return runtimeMaxFailures;
  }

  public void setRuntimeMaxFailures(int runtimeMaxFailures) {
    this.runtimeMaxFailures = runtimeMaxFailures;
  }

  public int getRuntimeMaxTokens() {
    return runtimeMaxTokens;
  }

  public void setRuntimeMaxTokens(int runtimeMaxTokens) {
    this.runtimeMaxTokens = runtimeMaxTokens;
  }

  public int getMaxResumeBytes() {
    return maxResumeBytes;
  }

  public void setMaxResumeBytes(int maxResumeBytes) {
    this.maxResumeBytes = maxResumeBytes;
  }

  public String getResumeRuntimeWorkspace() {
    return resumeRuntimeWorkspace;
  }

  public void setResumeRuntimeWorkspace(String resumeRuntimeWorkspace) {
    this.resumeRuntimeWorkspace = resumeRuntimeWorkspace;
  }

  public Auth getAuth() {
    return auth;
  }

  public void setAuth(Auth auth) {
    this.auth = auth;
  }

  public Minio getMinio() {
    return minio;
  }

  public void setMinio(Minio minio) {
    this.minio = minio;
  }

  /** Object storage settings for resume files. */
  public static class Minio {
    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String bucket;
    private String region = "";
    private boolean secure = true;
    private String signatureVersion = "v4";
    private boolean initializeBucket = true;

    public String getEndpoint() {
      return endpoint;
    }

    public void setEndpoint(String endpoint) {
      this.endpoint = endpoint;
    }

    public String getAccessKey() {
      return accessKey;
    }

    public void setAccessKey(String accessKey) {
      this.accessKey = accessKey;
    }

    public String getSecretKey() {
      return secretKey;
    }

    public void setSecretKey(String secretKey) {
      this.secretKey = secretKey;
    }

    public String getBucket() {
      return bucket;
    }

    public void setBucket(String bucket) {
      this.bucket = bucket;
    }

    public String getRegion() {
      return region;
    }

    public void setRegion(String region) {
      this.region = region;
    }

    public boolean isSecure() {
      return secure;
    }

    public void setSecure(boolean secure) {
      this.secure = secure;
    }

    public String getSignatureVersion() {
      return signatureVersion;
    }

    public void setSignatureVersion(String signatureVersion) {
      this.signatureVersion = signatureVersion;
    }

    public boolean isInitializeBucket() {
      return initializeBucket;
    }

    public void setInitializeBucket(boolean initializeBucket) {
      this.initializeBucket = initializeBucket;
    }
  }

  /** Local API authentication settings. */
  public static class Auth {
    private boolean enabled = true;
    private String internalApiToken = "";
    private String assetUrlSigningKey = "";
    private long assetUrlTtlSeconds = 3600L;
    private String bossCredentialEncryptionKey = "";

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getInternalApiToken() {
      return internalApiToken;
    }

    public void setInternalApiToken(String internalApiToken) {
      this.internalApiToken = internalApiToken;
    }

    public String getAssetUrlSigningKey() {
      return assetUrlSigningKey;
    }

    public void setAssetUrlSigningKey(String assetUrlSigningKey) {
      this.assetUrlSigningKey = assetUrlSigningKey;
    }

    public long getAssetUrlTtlSeconds() {
      return assetUrlTtlSeconds;
    }

    public void setAssetUrlTtlSeconds(long assetUrlTtlSeconds) {
      this.assetUrlTtlSeconds = assetUrlTtlSeconds;
    }

    public String getBossCredentialEncryptionKey() {
      return bossCredentialEncryptionKey;
    }

    public void setBossCredentialEncryptionKey(String bossCredentialEncryptionKey) {
      this.bossCredentialEncryptionKey = bossCredentialEncryptionKey;
    }
  }
}
