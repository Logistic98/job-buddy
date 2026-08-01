package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.security.AuthenticationScope;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.auth.client.BossBrowserClient;
import com.jobbuddy.backend.modules.auth.dto.internal.BossFavoriteListResult;
import com.jobbuddy.backend.modules.auth.exception.BossAuthRequiredException;
import com.jobbuddy.backend.modules.auth.service.impl.BossCliServiceImpl;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 验证 BossCliServiceImpl 的核心行为、异常路径与边界条件。
 */
class BossCliServiceImplTest {
  private static final JsonCodec JSON = new JsonCodec();

  /**
   * 清理请求级认证作用域。
   */
  @AfterEach
  void clearScope() {
    AuthenticationScope.clear();
  }

  /**
   * 验证 BossCliServiceImpl 中岗位的流式生命周期与中断边界。
   */
  @Test
  void searchJobsAcceptsHttpStyleSuccessEnvelope() {
    BossBrowserClient browserClient = mock(BossBrowserClient.class);
    Map<String, Object> job = new LinkedHashMap<String, Object>();
    job.put("jobName", "大模型应用开发");
    job.put("brandName", "Sample AI Co");
    when(browserClient.post(eq("/search"), anyMap()))
        .thenReturn(
            envelope(
                200,
                "success",
                Collections.<String, Object>singletonMap("jobs", Collections.singletonList(job))));
    BossCliServiceImpl service = newService(browserClient);
    IntentResult intent = new IntentResult();
    Map<String, Object> slots = new LinkedHashMap<String, Object>();
    slots.put("role", "大模型应用开发");
    slots.put("city", "杭州");
    intent.setSlots(slots);

    List<Map<String, Object>> jobs = service.searchJobsPage(intent, 1);

    assertEquals(1, jobs.size());
    assertEquals("大模型应用开发", jobs.get(0).get("jobName"));
  }

  /**
   * 已携带职位描述的最终推荐岗位只做本地透传，不应重复访问 Boss 详情接口。
   */
  @Test
  void enrichJobDetailsShouldSkipExistingDescriptionAndLoadOnlyMissingDetail() {
    BossBrowserClient browserClient = mock(BossBrowserClient.class);
    Map<String, Object> detail = new LinkedHashMap<String, Object>();
    detail.put("jobDescription", "负责云原生平台研发、稳定性治理和跨团队工程协作，要求具备完整生产系统经验。");
    when(browserClient.post(eq("/detail"), anyMap())).thenReturn(envelope(200, "success", detail));
    BossCliServiceImpl service = newService(browserClient);
    Map<String, Object> existing = new LinkedHashMap<String, Object>();
    existing.put("securityId", "sec-existing");
    existing.put("jobDescription", "搜索阶段已经取得的完整职位描述，包含岗位职责、任职要求和工程背景，不应再次触发详情请求。");
    Map<String, Object> missing = new LinkedHashMap<String, Object>();
    missing.put("securityId", "sec-missing");
    missing.put("originalUrl", "https://www.zhipin.com/job_detail/sec-missing.html");
    Map<String, Object> urlOnly = new LinkedHashMap<String, Object>();
    urlOnly.put(
        "jobUrl", "https://www.zhipin.com/job_detail/url-only.html?securityId=sec-url-only");

    List<Map<String, Object>> result =
        service.enrichJobDetails(java.util.Arrays.asList(existing, missing, urlOnly), 2);

    assertEquals(
        "搜索阶段已经取得的完整职位描述，包含岗位职责、任职要求和工程背景，不应再次触发详情请求。", result.get(0).get("jobDescription"));
    assertEquals(detail.get("jobDescription"), result.get(1).get("jobDescription"));
    assertEquals(detail.get("jobDescription"), result.get(2).get("jobDescription"));
    verify(browserClient, times(2)).post(eq("/detail"), anyMap());
    verify(browserClient)
        .post(
            eq("/detail"),
            argThat(
                payload ->
                    "sec-missing".equals(payload.get("securityId"))
                        && "https://www.zhipin.com/job_detail/sec-missing.html"
                            .equals(payload.get("url"))));
    verify(browserClient)
        .post(
            eq("/detail"),
            argThat(
                payload ->
                    "".equals(payload.get("securityId"))
                        && "https://www.zhipin.com/job_detail/url-only.html?securityId=sec-url-only"
                            .equals(payload.get("url"))));
  }

  /**
   * 最终推荐岗位按顺序补详情，首次失败后必须立即停手，避免继续放大 Boss 风控访问。
   */
  @Test
  void enrichJobDetailsShouldStopAfterFirstDetailFailure() {
    BossBrowserClient browserClient = mock(BossBrowserClient.class);
    when(browserClient.post(eq("/detail"), anyMap()))
        .thenReturn(envelope(5001, "temporary failure", null));
    BossCliServiceImpl service = newService(browserClient);
    Map<String, Object> first = new LinkedHashMap<String, Object>();
    first.put("securityId", "sec-failed");
    Map<String, Object> second = new LinkedHashMap<String, Object>();
    second.put("securityId", "sec-must-not-load");

    service.enrichJobDetails(java.util.Arrays.asList(first, second), 2);

    verify(browserClient, times(1))
        .post(eq("/detail"), argThat(payload -> "sec-failed".equals(payload.get("securityId"))));
  }

  /**
   * 携带 securityId 的岗位遇到“未拿到详情”的明确瞬时故障时，只即时补偿一次并合并第二次返回的 JD。
   */
  @ParameterizedTest
  @CsvSource({"未拿到岗位详情数据，请稍后重试。", "Boss 临时安全令牌刷新失败，请稍后重试。"})
  void enrichJobDetailsShouldRetryTransientBusinessFailureOnceAndMergeDescription(
      String transientMessage) {
    BossBrowserClient browserClient = mock(BossBrowserClient.class);
    Map<String, Object> detail = new LinkedHashMap<String, Object>();
    detail.put("jobDescription", "负责跨境电商推荐系统和大模型应用研发，要求具备完整线上系统交付经验。");
    when(browserClient.post(eq("/detail"), anyMap()))
        .thenReturn(envelope(5001, transientMessage, null), envelope(200, "success", detail));
    BossCliServiceImpl service = newService(browserClient);
    Map<String, Object> job = new LinkedHashMap<String, Object>();
    job.put("securityId", "sec-shopee");

    List<Map<String, Object>> result = service.enrichJobDetails(Collections.singletonList(job), 2);

    assertEquals(detail.get("jobDescription"), result.get(0).get("jobDescription"));
    verify(browserClient, times(2))
        .post(eq("/detail"), argThat(payload -> "sec-shopee".equals(payload.get("securityId"))));
  }

  /**
   * 第五张岗位首次返回空详情时，岗位数量预算已用满也仍应使用独立补偿预算取得 JD。
   */
  @Test
  void enrichJobDetailsShouldRetryLastJobOutsideJobCountBudget() {
    BossBrowserClient browserClient = mock(BossBrowserClient.class);
    Map<String, Object> detail = new LinkedHashMap<String, Object>();
    detail.put("jobDescription", "负责 Shopee 搜索推荐与大模型应用研发，建设高可用在线服务和完整效果评估体系。");
    when(browserClient.post(eq("/detail"), anyMap()))
        .thenReturn(
            envelope(200, "success", detail),
            envelope(200, "success", detail),
            envelope(200, "success", detail),
            envelope(200, "success", detail),
            envelope(5001, "未拿到岗位详情数据，请稍后重试。", null),
            envelope(200, "success", detail));
    BossCliServiceImpl service = newService(browserClient);
    List<Map<String, Object>> jobs = new java.util.ArrayList<Map<String, Object>>();
    for (int index = 1; index <= 5; index++) {
      Map<String, Object> job = new LinkedHashMap<String, Object>();
      job.put("securityId", "sec-shopee-" + index);
      jobs.add(job);
    }

    List<Map<String, Object>> result = service.enrichJobDetails(jobs, 5);

    assertEquals(detail.get("jobDescription"), result.get(4).get("jobDescription"));
    verify(browserClient, times(6)).post(eq("/detail"), anyMap());
    verify(browserClient, times(2))
        .post(eq("/detail"), argThat(payload -> "sec-shopee-5".equals(payload.get("securityId"))));
  }

  /**
   * 认证、验证码/风控与限流属于保护性失败，必须第一次失败后立即停手，不能即时重试。
   *
   * @param code Boss 业务码
   * @param message Boss 错误消息
   */
  @ParameterizedTest
  @CsvSource({
    "4001, Boss 登录态已失效，请重新扫码登录",
    "4002, 检测到 Boss 风控信号，请完成验证码安全验证",
    "4003, Boss 请求过于频繁，已触发限流冷却"
  })
  void enrichJobDetailsShouldNotRetryProtectedBossFailures(int code, String message) {
    BossBrowserClient browserClient = mock(BossBrowserClient.class);
    when(browserClient.post(eq("/detail"), anyMap())).thenReturn(envelope(code, message, null));
    BossCliServiceImpl service = newService(browserClient);
    Map<String, Object> job = new LinkedHashMap<String, Object>();
    job.put("securityId", "sec-protected-failure");

    service.enrichJobDetails(Collections.singletonList(job), 2);

    verify(browserClient, times(1)).post(eq("/detail"), anyMap());
  }

  /**
   * 仅有 URL 的岗位可能是 agent-tool 的本地拒绝结果，即使消息看似临时也不能补偿访问。
   */
  @Test
  void enrichJobDetailsShouldNotRetryTransientMessageWithoutSecurityId() {
    BossBrowserClient browserClient = mock(BossBrowserClient.class);
    when(browserClient.post(eq("/detail"), anyMap()))
        .thenReturn(
            envelope(5001, "未拿到岗位详情数据，请稍后重试。", null),
            envelope(
                200,
                "success",
                Collections.<String, Object>singletonMap("jobDescription", "不应被加载")));
    BossCliServiceImpl service = newService(browserClient);
    Map<String, Object> job = new LinkedHashMap<String, Object>();
    job.put("jobUrl", "https://www.zhipin.com/job_detail/url-only.html");

    List<Map<String, Object>> result = service.enrichJobDetails(Collections.singletonList(job), 2);

    assertFalse(result.get(0).containsKey("jobDescription"));
    verify(browserClient, times(1)).post(eq("/detail"), anyMap());
  }

  /**
   * encryptJobId/jobId 仅保留首次详情调用兼容，不能被当作真实 securityId 获得补偿访问。
   *
   * @param fallbackIdField 兼容岗位标识字段
   */
  @ParameterizedTest
  @CsvSource({"encryptJobId", "encrypt_job_id", "jobId", "job_id"})
  void enrichJobDetailsShouldNotRetryFallbackJobIdentifier(String fallbackIdField) {
    BossBrowserClient browserClient = mock(BossBrowserClient.class);
    when(browserClient.post(eq("/detail"), anyMap()))
        .thenReturn(
            envelope(5001, "未拿到岗位详情数据，请稍后重试。", null),
            envelope(
                200,
                "success",
                Collections.<String, Object>singletonMap("jobDescription", "不应被补偿加载")));
    BossCliServiceImpl service = newService(browserClient);
    Map<String, Object> job = new LinkedHashMap<String, Object>();
    job.put(fallbackIdField, "fallback-path-id");

    List<Map<String, Object>> result = service.enrichJobDetails(Collections.singletonList(job), 1);

    assertFalse(result.get(0).containsKey("jobDescription"));
    verify(browserClient, times(1))
        .post(
            eq("/detail"),
            argThat(payload -> "fallback-path-id".equals(payload.get("securityId"))));
  }

  /**
   * 未携带稳定 Boss 业务码的运行时异常不做即时补偿，避免把未知错误扩大成连续访问。
   */
  @Test
  void enrichJobDetailsShouldNotRetryGenericRuntimeException() {
    BossBrowserClient browserClient = mock(BossBrowserClient.class);
    when(browserClient.post(eq("/detail"), anyMap()))
        .thenThrow(new RuntimeException("Target page, context or browser has been closed"))
        .thenReturn(
            envelope(
                200,
                "success",
                Collections.<String, Object>singletonMap("jobDescription", "不应被补偿加载")));
    BossCliServiceImpl service = newService(browserClient);
    Map<String, Object> job = new LinkedHashMap<String, Object>();
    job.put("securityId", "sec-runtime-exception");

    List<Map<String, Object>> result = service.enrichJobDetails(Collections.singletonList(job), 2);

    assertFalse(result.get(0).containsKey("jobDescription"));
    verify(browserClient, times(1)).post(eq("/detail"), anyMap());
  }

  /**
   * 明确瞬时失败补偿一次后仍失败时，应停止当前批次，不得继续请求后续岗位。
   */
  @Test
  void enrichJobDetailsShouldStopBatchAfterSingleTransientRetryFails() {
    BossBrowserClient browserClient = mock(BossBrowserClient.class);
    when(browserClient.post(eq("/detail"), anyMap()))
        .thenReturn(envelope(5001, "未拿到岗位详情数据，请稍后重试。", null));
    BossCliServiceImpl service = newService(browserClient);
    Map<String, Object> first = new LinkedHashMap<String, Object>();
    first.put("securityId", "sec-retry-failed");
    Map<String, Object> second = new LinkedHashMap<String, Object>();
    second.put("securityId", "sec-must-not-load-after-retry");

    service.enrichJobDetails(java.util.Arrays.asList(first, second), 3);

    verify(browserClient, times(2))
        .post(
            eq("/detail"),
            argThat(payload -> "sec-retry-failed".equals(payload.get("securityId"))));
  }

  /**
   * 全批次补偿额度只能使用一次；首次补偿成功后，后续瞬时失败也应直接停手。
   */
  @Test
  void enrichJobDetailsShouldNotRetrySecondTransientFailureInSameBatch() {
    BossBrowserClient browserClient = mock(BossBrowserClient.class);
    Map<String, Object> recoveredDetail = new LinkedHashMap<String, Object>();
    recoveredDetail.put("jobDescription", "首次瞬时故障补偿成功后返回的完整岗位描述，长度足以作为最终展示内容。");
    when(browserClient.post(eq("/detail"), anyMap()))
        .thenReturn(
            envelope(5001, "未拿到岗位详情数据，请稍后重试。", null),
            envelope(200, "success", recoveredDetail),
            envelope(5001, "Boss 临时安全令牌刷新失败，请稍后重试。", null),
            envelope(200, "success", recoveredDetail));
    BossCliServiceImpl service = newService(browserClient);
    Map<String, Object> first = new LinkedHashMap<String, Object>();
    first.put("securityId", "sec-first-recovered");
    Map<String, Object> second = new LinkedHashMap<String, Object>();
    second.put("securityId", "sec-second-transient");
    Map<String, Object> third = new LinkedHashMap<String, Object>();
    third.put("securityId", "sec-must-not-load-after-second-failure");

    List<Map<String, Object>> result =
        service.enrichJobDetails(java.util.Arrays.asList(first, second, third), 3);

    assertEquals(recoveredDetail.get("jobDescription"), result.get(0).get("jobDescription"));
    assertFalse(result.get(1).containsKey("jobDescription"));
    verify(browserClient, times(3)).post(eq("/detail"), anyMap());
    verify(browserClient, times(1))
        .post(
            eq("/detail"),
            argThat(payload -> "sec-second-transient".equals(payload.get("securityId"))));
    verify(browserClient, times(0))
        .post(
            eq("/detail"),
            argThat(
                payload ->
                    "sec-must-not-load-after-second-failure".equals(payload.get("securityId"))));
  }

  /**
   * 验证 BossCliServiceImpl 中岗位的数量、长度与分页边界。
   */
  @Test
  void favoriteJobsReadsOnlyRequestedPageAndNormalizesCards() {
    AuthenticationScope.set("tenant-a", "user-a");
    BossBrowserClient browserClient = mock(BossBrowserClient.class);
    Map<String, Object> job = new LinkedHashMap<String, Object>();
    job.put("securityId", "sec-fav-1");
    job.put("jobName", "大模型应用开发岗");
    Map<String, Object> data = new LinkedHashMap<String, Object>();
    data.put("jobs", Collections.singletonList(job));
    data.put("page", 2);
    data.put("hasMore", true);
    data.put("totalCount", 12);
    data.put("totalPages", 3);
    when(browserClient.post(eq("/favorites"), anyMap())).thenReturn(envelope(200, "success", data));
    BossCliServiceImpl service = newService(browserClient);

    BossFavoriteListResult result = service.favoriteJobs(2);
    BossFavoriteListResult cached = service.favoriteJobs(2);
    BossFavoriteListResult refreshed = service.favoriteJobs(2, true);

    assertEquals(2, result.getPage());
    assertEquals(1, result.getJobs().size());
    assertEquals("sec-fav-1", result.getJobs().get(0).get("securityId").asText());
    assertEquals(true, result.isHasMore());
    assertEquals(3, result.getTotalPages());
    assertEquals(1, cached.getJobs().size());
    assertEquals(1, refreshed.getJobs().size());
    verify(browserClient, times(2)).post(eq("/favorites"), anyMap());
  }

  /**
   * 验证在线简历同步兼容 HTTP 成功响应封装。
   */
  @Test
  void fetchOnlineProfileAcceptsHttpStyleSuccessEnvelope() {
    BossBrowserClient browserClient = mock(BossBrowserClient.class);
    Map<String, Object> profile = new LinkedHashMap<String, Object>();
    profile.put("name", "测试候选人");
    when(browserClient.post(eq("/profile"), anyMap()))
        .thenReturn(envelope(200, "success", profile));
    BossCliServiceImpl service = newService(browserClient);

    Map<String, Object> result = JSON.toMap(service.fetchOnlineProfile());

    assertEquals("测试候选人", result.get("name"));
  }

  /**
   * 验证 BossCliServiceImpl 中凭据的身份认证与会话边界。
   */
  @Test
  void qrStatusShouldReturnCredentialOnlyAlongCurrentCallStack() {
    BossBrowserClient browserClient = mock(BossBrowserClient.class);
    Map<String, Object> data = new LinkedHashMap<String, Object>();
    data.put("authenticated", true);
    data.put("status", "logged_in");
    data.put("credential_json", "{\"cookies\":{\"wt2\":\"persisted\"}}");
    when(browserClient.post(eq("/login/qr/status"), anyMap()))
        .thenReturn(envelope(200, "success", data));
    BossCliServiceImpl service = newService(browserClient);

    Map<String, Object> result = JSON.toMap(service.qrStatus("qr1", "opaque-token"));

    assertEquals(true, result.get("ok"));
    Map<?, ?> resultData = (Map<?, ?>) result.get("data");
    assertEquals("{\"cookies\":{\"wt2\":\"persisted\"}}", resultData.get("credential_json"));
  }

  /**
   * 验证手机确认状态无需等待凭据派发即可返回前端。
   */
  @Test
  void qrStatusShouldExposeConfirmedStage() {
    BossBrowserClient browserClient = mock(BossBrowserClient.class);
    Map<String, Object> data = new LinkedHashMap<String, Object>();
    data.put("authenticated", false);
    data.put("status", "qr_confirmed");
    data.put("reason", "qr_confirmed");
    when(browserClient.post(eq("/login/qr/status"), anyMap()))
        .thenReturn(envelope(200, "success", data));
    BossCliServiceImpl service = newService(browserClient);

    Map<String, Object> result = JSON.toMap(service.qrStatus("qr1", "opaque-token"));

    Map<?, ?> resultData = (Map<?, ?>) result.get("data");
    assertEquals("confirmed", resultData.get("status"));
  }

  /**
   * 验证二维码快照显式关闭上游等待。
   */
  @Test
  void qrSnapshotShouldDisableUpstreamLongPolling() {
    BossBrowserClient browserClient = mock(BossBrowserClient.class);
    Map<String, Object> data = new LinkedHashMap<String, Object>();
    data.put("authenticated", false);
    data.put("status", "qr_waiting");
    data.put("image_base64", "snapshot-image");
    when(browserClient.post(eq("/login/qr/status"), anyMap()))
        .thenReturn(envelope(200, "success", data));
    BossCliServiceImpl service = newService(browserClient);

    Map<String, Object> result = JSON.toMap(service.qrSnapshot("qr1", "opaque-token"));

    Map<?, ?> resultData = (Map<?, ?>) result.get("data");
    assertEquals("waiting", resultData.get("status"));
    assertEquals("snapshot-image", resultData.get("image_base64"));
    verify(browserClient)
        .post(
            eq("/login/qr/status"),
            argThat(payload -> Boolean.FALSE.equals(payload.get("wait_for_update"))));
  }

  /**
   * 验证 BossCliServiceImpl 中认证的输入校验与拒绝边界。
   */
  @Test
  void statusDependencyFailureShouldNotLookLikeAuthRequired() {
    BossBrowserClient browserClient = mock(BossBrowserClient.class);
    when(browserClient.get("/status")).thenReturn(envelope(5001, "runtime unavailable", null));
    BossCliServiceImpl service = newService(browserClient);

    Map<String, Object> result = JSON.toMap(service.status());

    assertEquals("error", result.get("status"));
    assertFalse(Boolean.TRUE.equals(result.get("authenticated")));
  }

  /**
   * 验证 BossCliServiceImpl 中岗位的输入校验与拒绝边界。
   */
  @Test
  void searchJobsStillRoutesAuthRequiredEnvelope() {
    BossBrowserClient browserClient = mock(BossBrowserClient.class);
    when(browserClient.post(eq("/search"), anyMap()))
        .thenReturn(envelope(4001, "auth required", null));
    BossCliServiceImpl service = newService(browserClient);

    assertThrows(
        BossAuthRequiredException.class, () -> service.searchJobsPage(new IntentResult(), 1));
  }

  /**
   * 验证新建服务。
   *
   * @param browserClient 浏览器客户端
   * @return 服务
   */
  private BossCliServiceImpl newService(BossBrowserClient browserClient) {
    return new BossCliServiceImpl(
        browserClient, mock(ApplicationEventPublisher.class), new JobBuddyProperties());
  }

  /**
   * 验证响应封装。
   *
   * @param code 编码
   * @param message 消息内容
   * @param data 数据
   * @return 模拟下游响应
   */
  private Map<String, Object> envelope(int code, String message, Object data) {
    Map<String, Object> envelope = new LinkedHashMap<String, Object>();
    envelope.put("code", code);
    envelope.put("message", message);
    envelope.put("data", data);
    return envelope;
  }
}
