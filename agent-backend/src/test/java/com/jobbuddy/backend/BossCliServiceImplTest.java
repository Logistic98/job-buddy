package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
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
    slots.put("city", "上海");
    intent.setSlots(slots);

    List<Map<String, Object>> jobs = service.searchJobsPage(intent, 1);

    assertEquals(1, jobs.size());
    assertEquals("大模型应用开发", jobs.get(0).get("jobName"));
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
