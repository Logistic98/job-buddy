package com.jobbuddy.backend.modules.auth.service;

import com.jobbuddy.backend.modules.chat.vo.IntentResult;

import java.util.List;
import java.util.Map;

public interface BossCliService {
    Map<String, Object> status();
    String readCredentialJson();
    boolean hasLocalCredential();
    /** 本地登录标记缺失时物化一份最小化的 logged_in 标记并返回其内容，用于扫码成功后可靠落库与重启回填。 */
    String ensureLoginMarker();
    boolean restoreCredentialIfMissing(String credentialJson);
    boolean writeCredential(String credentialJson);
    boolean isAuthenticated();
    Map<String, Object> loginInstructions();
    Map<String, Object> qrStart();
    Map<String, Object> qrStatus(String sessionId);
    Map<String, Object> qrCancel(String sessionId);
    Map<String, Object> cancelLogin();
    Map<String, Object> fetchOnlineProfile();
    Map<String, Object> jobDetail(String securityId, String url);
    List<Map<String, Object>> searchJobs(IntentResult intent);
    List<Map<String, Object>> searchJobs(IntentResult intent, int targetCount);
    List<Map<String, Object>> searchJobsFirstPage(IntentResult intent, JobBatchConsumer consumer);
    List<Map<String, Object>> searchJobsPage(IntentResult intent, int page);
    List<Map<String, Object>> searchJobsBatches(IntentResult intent, int targetCount, JobBatchConsumer consumer);
    List<Map<String, Object>> enrichJobDetails(List<Map<String, Object>> jobs, int maxDetails);

    interface JobBatchConsumer {
        void accept(List<Map<String, Object>> accumulated, List<Map<String, Object>> latestBatch, String query, int page);
    }
}
