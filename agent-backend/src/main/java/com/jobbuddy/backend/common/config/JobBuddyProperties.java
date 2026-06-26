package com.jobbuddy.backend.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Centralized configuration bound from {@code job-buddy.*} properties.
 */
@Component
@ConfigurationProperties(prefix = "job-buddy")
public class JobBuddyProperties {

    private String defaultUserId = "default-user";

    /** Job recommendation and scoring safeguards. */
    private int maxJobsPerRecommend = 15;
    private int recommendOverfetchFactor = 5;
    private int maxJobsPerScoring = 80;

    /** Boss live-search throttling and cache settings. */
    private int bossSearchMaxPages = 2;
    private int bossSearchTargetCandidates = 45;
    private int bossSearchMaxPageDepth = 5;
    private int bossSearchPageDelayMillis = 5000;
    private int bossSearchCacheTtlMinutes = 30;
    private int bossSearchCooldownMinutesOnRisk = 30;
    private boolean bossLiveEnabled = true;
    private String bossWebBaseUrl = "https://www.zhipin.com";

    /**
     * 高风险意图安全门控开关。开启后，agent-intent 预判为高风险且建议拒绝的请求会被独立拦截，
     * 不再进入 runtime 链路。属于用户可见行为变更，默认关闭，需完成浏览器端到端验证后再开启。
     */
    private boolean intentSafetyGateEnabled = false;

    /** Runtime budget limits used when delegating Agent execution. */
    private int runtimeMaxTurns = 4;
    private int runtimeMaxToolCalls = 4;
    private int runtimeMaxFailures = 2;

    /** Resume upload and object-storage configuration. */
    private int maxResumeBytes = 5 * 1024 * 1024;
    private String resumeRuntimeWorkspace = "";
    private Auth auth = new Auth();
    private Minio minio = new Minio();

    public String getDefaultUserId() {
        return defaultUserId;
    }

    public void setDefaultUserId(String defaultUserId) {
        this.defaultUserId = defaultUserId;
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

    public boolean isBossLiveEnabled() {
        return bossLiveEnabled;
    }

    public void setBossLiveEnabled(boolean bossLiveEnabled) {
        this.bossLiveEnabled = bossLiveEnabled;
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

    /**
     * Object storage settings for resume files.
     */
    public static class Minio {
        private String endpoint;
        private String accessKey;
        private String secretKey;
        private String bucket;
        private String region = "";
        private boolean secure = true;

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
    }

    /**
     * Local API authentication settings.
     */
    public static class Auth {
        private boolean enabled = true;
        private String internalApiToken = "";
        private String assetUrlSigningKey = "";
        private long assetUrlTtlSeconds = 3600L;

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
    }
}
