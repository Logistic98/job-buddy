package com.jobbuddy.backend.common.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 统一绑定 {@code job-buddy.*} 配置。
 */
@Component
@ConfigurationProperties(prefix = "job-buddy")
public class JobBuddyProperties {

  private String environment = "development";
  private String defaultUserId = "default-user";
  private List<String> corsAllowedOrigins =
      new ArrayList<String>(List.of("http://localhost:5173", "http://127.0.0.1:5173"));

  /**
   * 岗位推荐与评分保护参数。
   */
  // 平台设置会在进程运行期间更新这些字段；volatile 保证 SSE 和候选池工作线程立即可见。
  private volatile int maxJobsPerRecommend = 5;

  private volatile int recommendOverfetchFactor = 3;
  private volatile int maxJobsPerScoring = 30;
  private volatile int minimumRecommendedMatchScore = 60;

  /**
   * Boss 实时检索限流与缓存参数。
   */
  private volatile int bossSearchMaxPages = 3;

  private volatile int bossSearchTargetCandidates = 15;
  private volatile int bossSearchMaxPageDepth = 15;
  private volatile int bossSearchPageDelayMillis = 3000;
  private volatile int bossSearchCacheTtlMinutes = 30;
  private volatile int bossSearchCooldownMinutesOnRisk = 30;
  private String bossWebBaseUrl = "https://www.zhipin.com";

  /**
   * 高风险意图安全门控开关。开启后，agent-intent 预判为高风险且建议拒绝的请求会被独立拦截， 不再进入 runtime
   * 链路。属于用户可见行为变更，默认关闭，需完成浏览器端到端验证后再开启。
   */
  private boolean intentSafetyGateEnabled = false;

  /**
   * 委派 Agent 执行时使用的 Runtime 预算上限。
   */
  private volatile int runtimeMaxTurns = 12;

  private volatile int runtimeMaxToolCalls = 20;
  private volatile int runtimeMaxFailures = 3;
  private int runtimeMaxTokens = 32768;

  /**
   * 简历上传与对象存储配置。
   */
  private volatile int maxResumeBytes = 5 * 1024 * 1024;

  private String resumeRuntimeWorkspace = "";

  /**
   * 简历撰写版本历史:每个用户保留的最大版本数与单版本快照大小上限。
   */
  private volatile int resumeWriterVersionLimit = 30;

  private int resumeWriterSnapshotMaxBytes = 2 * 1024 * 1024;
  private Auth auth = new Auth();
  private Minio minio = new Minio();

  /**
   * 获取运行环境。
   *
   * @return 运行环境
   */
  public String getEnvironment() {
    return environment;
  }

  /**
   * 设置运行环境。
   *
   * @param environment 运行环境
   */
  public void setEnvironment(String environment) {
    this.environment = environment;
  }

  /**
   * 获取默认用户标识。
   *
   * @return 默认用户标识
   */
  public String getDefaultUserId() {
    return defaultUserId;
  }

  /**
   * 获取简历撰写器版本上限。
   *
   * @return 简历撰写版本上限
   */
  public int getResumeWriterVersionLimit() {
    return resumeWriterVersionLimit;
  }

  /**
   * 设置简历撰写器版本上限。
   *
   * @param resumeWriterVersionLimit 简历撰写器版本上限
   */
  public void setResumeWriterVersionLimit(int resumeWriterVersionLimit) {
    this.resumeWriterVersionLimit = resumeWriterVersionLimit;
  }

  /**
   * 获取简历撰写快照的最大字节数。
   *
   * @return 简历撰写器快照最大字节数
   */
  public int getResumeWriterSnapshotMaxBytes() {
    return resumeWriterSnapshotMaxBytes;
  }

  /**
   * 设置简历撰写快照的最大字节数。
   *
   * @param resumeWriterSnapshotMaxBytes 简历撰写快照最大字节数
   */
  public void setResumeWriterSnapshotMaxBytes(int resumeWriterSnapshotMaxBytes) {
    this.resumeWriterSnapshotMaxBytes = resumeWriterSnapshotMaxBytes;
  }

  /**
   * 设置默认用户标识。
   *
   * @param defaultUserId 默认用户标识
   */
  public void setDefaultUserId(String defaultUserId) {
    this.defaultUserId = defaultUserId;
  }

  /**
   * 获取 CORS 允许来源。
   *
   * @return 允许的 CORS 来源
   */
  public List<String> getCorsAllowedOrigins() {
    return corsAllowedOrigins;
  }

  /**
   * 设置 CORS 允许来源。
   *
   * @param corsAllowedOrigins CORS 允许来源列表
   */
  public void setCorsAllowedOrigins(List<String> corsAllowedOrigins) {
    this.corsAllowedOrigins =
        corsAllowedOrigins == null
            ? new ArrayList<String>()
            : new ArrayList<String>(corsAllowedOrigins);
  }

  /**
   * 获取单次推荐的最大岗位数。
   *
   * @return 单次推荐最大岗位数
   */
  public int getMaxJobsPerRecommend() {
    return maxJobsPerRecommend;
  }

  /**
   * 设置单次推荐的最大岗位数。
   *
   * @param maxJobsPerRecommend 单次推荐最大岗位数
   */
  public void setMaxJobsPerRecommend(int maxJobsPerRecommend) {
    this.maxJobsPerRecommend = maxJobsPerRecommend;
  }

  /**
   * 获取推荐超量抓取系数。
   *
   * @return 推荐超额召回系数
   */
  public int getRecommendOverfetchFactor() {
    return recommendOverfetchFactor;
  }

  /**
   * 设置推荐超量抓取系数。
   *
   * @param recommendOverfetchFactor 推荐超额召回倍数
   */
  public void setRecommendOverfetchFactor(int recommendOverfetchFactor) {
    this.recommendOverfetchFactor = recommendOverfetchFactor;
  }

  /**
   * 获取单批评分的最大岗位数。
   *
   * @return 单批评分最大岗位数
   */
  public int getMaxJobsPerScoring() {
    return maxJobsPerScoring;
  }

  /**
   * 设置单批评分的最大岗位数。
   *
   * @param maxJobsPerScoring 单批评分最大岗位数
   */
  public void setMaxJobsPerScoring(int maxJobsPerScoring) {
    this.maxJobsPerScoring = maxJobsPerScoring;
  }

  /**
   * 获取最低推荐匹配评分。
   *
   * @return 最低推荐匹配分数
   */
  public int getMinimumRecommendedMatchScore() {
    return minimumRecommendedMatchScore;
  }

  /**
   * 设置最低推荐匹配评分。
   *
   * @param minimumRecommendedMatchScore 最低推荐匹配分数
   */
  public void setMinimumRecommendedMatchScore(int minimumRecommendedMatchScore) {
    this.minimumRecommendedMatchScore = minimumRecommendedMatchScore;
  }

  /**
   * 获取 Boss 检索最大页数。
   *
   * @return Boss 搜索最大页数
   */
  public int getBossSearchMaxPages() {
    return bossSearchMaxPages;
  }

  /**
   * 设置 Boss 检索最大页数。
   *
   * @param bossSearchMaxPages Boss 检索最大页数
   */
  public void setBossSearchMaxPages(int bossSearchMaxPages) {
    this.bossSearchMaxPages = bossSearchMaxPages;
  }

  /**
   * 获取 Boss 检索目标候选岗位列表。
   *
   * @return Boss 搜索目标候选数
   */
  public int getBossSearchTargetCandidates() {
    return bossSearchTargetCandidates;
  }

  /**
   * 设置 Boss 检索目标候选岗位列表。
   *
   * @param bossSearchTargetCandidates Boss 检索目标候选岗位列表
   */
  public void setBossSearchTargetCandidates(int bossSearchTargetCandidates) {
    this.bossSearchTargetCandidates = bossSearchTargetCandidates;
  }

  /**
   * 获取 Boss 检索最大分页深度。
   *
   * @return Boss 搜索最大页码深度
   */
  public int getBossSearchMaxPageDepth() {
    return bossSearchMaxPageDepth;
  }

  /**
   * 设置 Boss 检索最大分页深度。
   *
   * @param bossSearchMaxPageDepth Boss 搜索最大页深度
   */
  public void setBossSearchMaxPageDepth(int bossSearchMaxPageDepth) {
    this.bossSearchMaxPageDepth = bossSearchMaxPageDepth;
  }

  /**
   * 获取 Boss 检索分页延迟毫秒数。
   *
   * @return Boss 搜索页码延迟毫秒数
   */
  public int getBossSearchPageDelayMillis() {
    return bossSearchPageDelayMillis;
  }

  /**
   * 设置 Boss 检索分页延迟毫秒数。
   *
   * @param bossSearchPageDelayMillis Boss 搜索页延迟毫秒数
   */
  public void setBossSearchPageDelayMillis(int bossSearchPageDelayMillis) {
    this.bossSearchPageDelayMillis = bossSearchPageDelayMillis;
  }

  /**
   * 获取 Boss 检索缓存有效期分钟。
   *
   * @return Boss 搜索缓存有效期分钟数
   */
  public int getBossSearchCacheTtlMinutes() {
    return bossSearchCacheTtlMinutes;
  }

  /**
   * 设置 Boss 检索缓存有效期分钟。
   *
   * @param bossSearchCacheTtlMinutes Boss 检索缓存有效期分钟
   */
  public void setBossSearchCacheTtlMinutes(int bossSearchCacheTtlMinutes) {
    this.bossSearchCacheTtlMinutes = bossSearchCacheTtlMinutes;
  }

  /**
   * 获取 Boss 风控后的冷却分钟数。
   *
   * @return 触发风控后的冷却分钟数
   */
  public int getBossSearchCooldownMinutesOnRisk() {
    return bossSearchCooldownMinutesOnRisk;
  }

  /**
   * 设置 Boss 风控后的冷却分钟数。
   *
   * @param bossSearchCooldownMinutesOnRisk Boss 风控后的冷却分钟数
   */
  public void setBossSearchCooldownMinutesOnRisk(int bossSearchCooldownMinutesOnRisk) {
    this.bossSearchCooldownMinutesOnRisk = bossSearchCooldownMinutesOnRisk;
  }

  /**
   * 获取 Boss Web 基础 URL。
   *
   * @return Boss Web 基础 URL
   */
  public String getBossWebBaseUrl() {
    return bossWebBaseUrl;
  }

  /**
   * 设置 Boss Web 基础 URL。
   *
   * @param bossWebBaseUrl Boss Web 基础 URL
   */
  public void setBossWebBaseUrl(String bossWebBaseUrl) {
    this.bossWebBaseUrl = bossWebBaseUrl;
  }

  /**
   * 判断意图安全门禁是否启用。
   *
   * @return 是否启用意图安全门禁
   */
  public boolean isIntentSafetyGateEnabled() {
    return intentSafetyGateEnabled;
  }

  /**
   * 设置意图安全门禁启用状态。
   *
   * @param intentSafetyGateEnabled 意图安全门禁是否启用
   */
  public void setIntentSafetyGateEnabled(boolean intentSafetyGateEnabled) {
    this.intentSafetyGateEnabled = intentSafetyGateEnabled;
  }

  /**
   * 获取运行时最大轮次。
   *
   * @return 运行时最大轮次
   */
  public int getRuntimeMaxTurns() {
    return runtimeMaxTurns;
  }

  /**
   * 设置运行时最大轮次。
   *
   * @param runtimeMaxTurns 运行时最大轮次
   */
  public void setRuntimeMaxTurns(int runtimeMaxTurns) {
    this.runtimeMaxTurns = runtimeMaxTurns;
  }

  /**
   * 获取 Runtime 最大工具调用次数。
   *
   * @return 运行时最大工具调用次数
   */
  public int getRuntimeMaxToolCalls() {
    return runtimeMaxToolCalls;
  }

  /**
   * 设置 Runtime 最大工具调用次数。
   *
   * @param runtimeMaxToolCalls 运行时最大工具调用次数
   */
  public void setRuntimeMaxToolCalls(int runtimeMaxToolCalls) {
    this.runtimeMaxToolCalls = runtimeMaxToolCalls;
  }

  /**
   * 获取 Runtime 最大失败次数。
   *
   * @return 运行时最大失败次数
   */
  public int getRuntimeMaxFailures() {
    return runtimeMaxFailures;
  }

  /**
   * 设置 Runtime 最大失败次数。
   *
   * @param runtimeMaxFailures 运行时最大失败次数
   */
  public void setRuntimeMaxFailures(int runtimeMaxFailures) {
    this.runtimeMaxFailures = runtimeMaxFailures;
  }

  /**
   * 获取 Runtime 最大令牌数。
   *
   * @return 运行时最大令牌数
   */
  public int getRuntimeMaxTokens() {
    return runtimeMaxTokens;
  }

  /**
   * 设置 Runtime 最大令牌数。
   *
   * @param runtimeMaxTokens 运行时最大令牌数
   */
  public void setRuntimeMaxTokens(int runtimeMaxTokens) {
    this.runtimeMaxTokens = runtimeMaxTokens;
  }

  /**
   * 获取简历最大字节数。
   *
   * @return 最大简历字节数
   */
  public int getMaxResumeBytes() {
    return maxResumeBytes;
  }

  /**
   * 设置简历最大字节数。
   *
   * @param maxResumeBytes 简历最大字节数
   */
  public void setMaxResumeBytes(int maxResumeBytes) {
    this.maxResumeBytes = maxResumeBytes;
  }

  /**
   * 获取简历运行时工作区。
   *
   * @return 简历运行时工作区
   */
  public String getResumeRuntimeWorkspace() {
    return resumeRuntimeWorkspace;
  }

  /**
   * 设置简历运行时工作区。
   *
   * @param resumeRuntimeWorkspace 简历运行时工作区
   */
  public void setResumeRuntimeWorkspace(String resumeRuntimeWorkspace) {
    this.resumeRuntimeWorkspace = resumeRuntimeWorkspace;
  }

  /**
   * 获取认证配置。
   *
   * @return 认证配置
   */
  public Auth getAuth() {
    return auth;
  }

  /**
   * 设置认证配置。
   *
   * @param auth 认证配置
   */
  public void setAuth(Auth auth) {
    this.auth = auth;
  }

  /**
   * 获取 MinIO 配置。
   *
   * @return MinIO 配置
   */
  public Minio getMinio() {
    return minio;
  }

  /**
   * 设置 MinIO 配置。
   *
   * @param minio MinIO 配置
   */
  public void setMinio(Minio minio) {
    this.minio = minio;
  }

  /**
   * 简历文件对象存储参数。
   */
  public static class Minio {
    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String bucket;
    private String region = "";
    private boolean secure = true;
    private String signatureVersion = "v4";
    private boolean initializeBucket = true;

    /**
     * 获取服务端点。
     *
     * @return 服务地址
     */
    public String getEndpoint() {
      return endpoint;
    }

    /**
     * 设置服务端点。
     *
     * @param endpoint 服务地址
     */
    public void setEndpoint(String endpoint) {
      this.endpoint = endpoint;
    }

    /**
     * 获取访问密钥标识。
     *
     * @return 访问密钥标识
     */
    public String getAccessKey() {
      return accessKey;
    }

    /**
     * 设置访问密钥标识。
     *
     * @param accessKey 访问密钥标识
     */
    public void setAccessKey(String accessKey) {
      this.accessKey = accessKey;
    }

    /**
     * 获取访问密钥。
     *
     * @return 访问密钥
     */
    public String getSecretKey() {
      return secretKey;
    }

    /**
     * 设置访问密钥。
     *
     * @param secretKey 访问密钥
     */
    public void setSecretKey(String secretKey) {
      this.secretKey = secretKey;
    }

    /**
     * 获取存储桶。
     *
     * @return 存储桶
     */
    public String getBucket() {
      return bucket;
    }

    /**
     * 设置存储桶。
     *
     * @param bucket 存储桶
     */
    public void setBucket(String bucket) {
      this.bucket = bucket;
    }

    /**
     * 获取区域。
     *
     * @return 存储区域
     */
    public String getRegion() {
      return region;
    }

    /**
     * 设置区域。
     *
     * @param region 区域
     */
    public void setRegion(String region) {
      this.region = region;
    }

    /**
     * 判断是否安全连接。
     *
     * @return 是否使用安全连接
     */
    public boolean isSecure() {
      return secure;
    }

    /**
     * 设置安全连接。
     *
     * @param secure 是否使用 HTTPS
     */
    public void setSecure(boolean secure) {
      this.secure = secure;
    }

    /**
     * 获取签名版本。
     *
     * @return 签名版本
     */
    public String getSignatureVersion() {
      return signatureVersion;
    }

    /**
     * 设置签名版本。
     *
     * @param signatureVersion 签名版本
     */
    public void setSignatureVersion(String signatureVersion) {
      this.signatureVersion = signatureVersion;
    }

    /**
     * 判断是否初始化存储桶。
     *
     * @return 是否自动初始化存储桶
     */
    public boolean isInitializeBucket() {
      return initializeBucket;
    }

    /**
     * 设置初始化存储桶。
     *
     * @param initializeBucket 是否初始化存储桶
     */
    public void setInitializeBucket(boolean initializeBucket) {
      this.initializeBucket = initializeBucket;
    }
  }

  /**
   * 本地 API 认证参数。
   */
  public static class Auth {
    private boolean enabled = true;
    private String internalApiToken = "";
    private String assetUrlSigningKey = "";
    private long assetUrlTtlSeconds = 3600L;
    private String bossCredentialEncryptionKey = "";

    /**
     * 判断认证是否启用。
     *
     * @return 是否启用认证
     */
    public boolean isEnabled() {
      return enabled;
    }

    /**
     * 设置启用状态。
     *
     * @param enabled 启用状态
     */
    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    /**
     * 获取内部 API 令牌。
     *
     * @return 内部 API 令牌
     */
    public String getInternalApiToken() {
      return internalApiToken;
    }

    /**
     * 设置内部 API 令牌。
     *
     * @param internalApiToken 内部 API 令牌
     */
    public void setInternalApiToken(String internalApiToken) {
      this.internalApiToken = internalApiToken;
    }

    /**
     * 获取资源 URL 签名键。
     *
     * @return 资源 URL 签名键
     */
    public String getAssetUrlSigningKey() {
      return assetUrlSigningKey;
    }

    /**
     * 设置资源 URL 签名键。
     *
     * @param assetUrlSigningKey 资源 URL 签名键
     */
    public void setAssetUrlSigningKey(String assetUrlSigningKey) {
      this.assetUrlSigningKey = assetUrlSigningKey;
    }

    /**
     * 获取资源 URL 有效期秒。
     *
     * @return 附件 URL 有效期秒数
     */
    public long getAssetUrlTtlSeconds() {
      return assetUrlTtlSeconds;
    }

    /**
     * 设置资源 URL 有效期秒。
     *
     * @param assetUrlTtlSeconds 资源 URL 有效期秒
     */
    public void setAssetUrlTtlSeconds(long assetUrlTtlSeconds) {
      this.assetUrlTtlSeconds = assetUrlTtlSeconds;
    }

    /**
     * 获取 Boss 凭据加密键。
     *
     * @return Boss 凭据加密键
     */
    public String getBossCredentialEncryptionKey() {
      return bossCredentialEncryptionKey;
    }

    /**
     * 设置 Boss 凭据加密键。
     *
     * @param bossCredentialEncryptionKey Boss 凭据加密键
     */
    public void setBossCredentialEncryptionKey(String bossCredentialEncryptionKey) {
      this.bossCredentialEncryptionKey = bossCredentialEncryptionKey;
    }
  }
}
