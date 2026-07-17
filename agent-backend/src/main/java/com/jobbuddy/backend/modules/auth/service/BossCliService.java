package com.jobbuddy.backend.modules.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobbuddy.backend.modules.auth.dto.internal.BossCliCancelResult;
import com.jobbuddy.backend.modules.auth.dto.internal.BossCliQrResult;
import com.jobbuddy.backend.modules.auth.dto.internal.BossCliStatusResult;
import com.jobbuddy.backend.modules.auth.dto.internal.BossFavoriteListResult;
import com.jobbuddy.backend.modules.auth.dto.response.BossLoginStatusResponse;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import java.util.List;
import java.util.Map;

public interface BossCliService {
  BossCliStatusResult status();

  boolean isAuthenticated();

  BossLoginStatusResponse loginInstructions();

  BossCliQrResult qrStart();

  BossCliQrResult qrStatus(String sessionId);

  BossCliCancelResult qrCancel(String sessionId);

  BossCliCancelResult cancelLogin();

  // 画像、岗位详情和搜索结果来自 agent-tool/Boss，字段不稳定，属于明确外部 JSON 边界。
  JsonNode fetchOnlineProfile();

  JsonNode jobDetail(String securityId, String url);

  BossFavoriteListResult favoriteJobs(int page);

  BossFavoriteListResult favoriteJobs(int page, boolean forceRefresh);

  List<Map<String, Object>> searchJobs(IntentResult intent);

  List<Map<String, Object>> searchJobs(IntentResult intent, int targetCount);

  List<Map<String, Object>> searchJobsFirstPage(IntentResult intent);

  List<Map<String, Object>> searchJobsPage(IntentResult intent, int page);

  List<Map<String, Object>> searchJobsBatches(
      IntentResult intent, int targetCount, JobBatchConsumer consumer);

  List<Map<String, Object>> enrichJobDetails(List<Map<String, Object>> jobs, int maxDetails);

  interface JobBatchConsumer {
    void accept(
        List<Map<String, Object>> accumulated,
        List<Map<String, Object>> latestBatch,
        String query,
        int page);
  }
}
