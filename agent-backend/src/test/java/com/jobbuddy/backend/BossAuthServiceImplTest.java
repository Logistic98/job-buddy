package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.security.AuthenticationScope;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.auth.dto.internal.BossCliCancelResult;
import com.jobbuddy.backend.modules.auth.dto.internal.BossCliQrResult;
import com.jobbuddy.backend.modules.auth.dto.internal.BossCliStatusResult;
import com.jobbuddy.backend.modules.auth.repository.AuthStateRepository;
import com.jobbuddy.backend.modules.auth.service.BossCliService;
import com.jobbuddy.backend.modules.auth.service.impl.BossAuthServiceImpl;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 验证 BossAuthServiceImpl 的核心行为、异常路径与边界条件。
 */
class BossAuthServiceImplTest {
  private static final JsonCodec JSON = new JsonCodec();

  /**
   * 清理请求级认证作用域。
   */
  @AfterEach
  void clearScope() {
    AuthenticationScope.clear();
  }

  /**
   * 验证 BossAuthServiceImpl 中凭据的输入校验与拒绝边界。
   */
  @Test
  void loginStatusCheckFailureShouldNotMarkCredentialInvalid() {
    AuthenticationScope.set("tenant-a", "user-a");
    BossCliService bossCli = mock(BossCliService.class);
    AuthStateRepository repository = mock(AuthStateRepository.class);
    BossCliStatusResult failed = status("error", false);
    failed.setError(JSON.toTree("runtime unavailable"));
    when(bossCli.status()).thenReturn(failed);

    BossAuthServiceImpl service = new BossAuthServiceImpl(bossCli, repository);

    assertThrows(RuntimeException.class, () -> service.loginStatus("s1", null));
    verify(repository, never())
        .updateStatus(eq("jackwener/boss-cli"), eq("auth_required"), any(Map.class));
  }

  /**
   * 验证 BossAuthServiceImpl 中用户的权限与租户隔离边界。
   */
  @Test
  void authenticationCacheMustBeIsolatedByTenantAndUser() {
    BossCliService bossCli = mock(BossCliService.class);
    AuthStateRepository repository = mock(AuthStateRepository.class);
    when(bossCli.status()).thenReturn(status("logged_in", true), status("auth_required", false));
    BossAuthServiceImpl service = new BossAuthServiceImpl(bossCli, repository);

    AuthenticationScope.set("tenant-a", "user-a");
    Map<String, Object> first = JSON.toMap(service.loginStatus("s1", null));
    Map<String, Object> cached = JSON.toMap(service.loginStatus("s1", null));
    AuthenticationScope.set("tenant-a", "user-b");
    Map<String, Object> secondUser = JSON.toMap(service.loginStatus("s2", null));

    assertTrue(Boolean.TRUE.equals(first.get("ok")));
    assertTrue(Boolean.TRUE.equals(cached.get("ok")));
    assertFalse(Boolean.TRUE.equals(secondUser.get("ok")));
    verify(bossCli, times(2)).status();
  }

  /**
   * 验证 BossAuthServiceImpl 的权限与租户隔离边界。
   */
  @Test
  void qrStartMustPersistCurrentOwnerWithServerSideExpiry() {
    AuthenticationScope.set("tenant-a", "user-a");
    BossCliService bossCli = mock(BossCliService.class);
    AuthStateRepository repository = mock(AuthStateRepository.class);
    when(bossCli.status()).thenReturn(status("auth_required", false));
    Map<String, Object> data = new LinkedHashMap<String, Object>();
    data.put("session_id", "qr-a");
    data.put("session_token", "opaque-tool-session");
    data.put("status", "qr_ready");
    when(bossCli.qrStart()).thenReturn(envelope(data));
    BossAuthServiceImpl service = new BossAuthServiceImpl(bossCli, repository);

    Map<String, Object> response = JSON.toMap(service.startQrLogin("chat-a"));

    assertEquals("qr-a", response.get("qrSessionId"));
    verify(repository)
        .saveQrSession(
            eq("tenant-a"),
            eq("user-a"),
            eq("chat-a"),
            eq("qr-a"),
            eq("opaque-tool-session"),
            any(Instant.class));
  }

  /**
   * 验证不同入口共享活动二维码会话。
   */
  @Test
  void activeQrSessionShouldBeSharedAcrossEntryPoints() {
    AuthenticationScope.set("tenant-a", "user-a");
    BossCliService bossCli = mock(BossCliService.class);
    AuthStateRepository repository = mock(AuthStateRepository.class);
    when(bossCli.status()).thenReturn(status("auth_required", false));
    when(repository.findActiveQrSession("tenant-a", "user-a"))
        .thenReturn(qrOwner("tenant-a", "user-a", "qr-a"));
    when(repository.findQrSession("qr-a")).thenReturn(qrOwner("tenant-a", "user-a", "qr-a"));
    Map<String, Object> waiting = new LinkedHashMap<String, Object>();
    waiting.put("status", "waiting");
    waiting.put("image_base64", "shared-qr-image");
    when(bossCli.qrStatus("qr-a", "token-qr-a")).thenReturn(envelope(waiting));
    BossAuthServiceImpl service = new BossAuthServiceImpl(bossCli, repository);

    Map<String, Object> response = JSON.toMap(service.startQrLogin("jobs-import"));

    assertEquals("qr-a", response.get("qrSessionId"));
    assertEquals("shared-qr-image", response.get("imageBase64"));
    verify(bossCli, never()).qrStart();
  }

  /**
   * 验证 BossAuthServiceImpl 中工具的权限与租户隔离边界。
   */
  @Test
  void qrOwnerMismatchMustFailBeforePollingTool() {
    AuthenticationScope.set("tenant-b", "user-b");
    BossCliService bossCli = mock(BossCliService.class);
    AuthStateRepository repository = mock(AuthStateRepository.class);
    when(repository.findQrSession("qr-a")).thenReturn(qrOwner("tenant-a", "user-a", "qr-a"));
    BossAuthServiceImpl service = new BossAuthServiceImpl(bossCli, repository);

    assertThrows(IllegalArgumentException.class, () -> service.loginStatus("chat-b", "qr-a"));
    verify(bossCli, never()).qrStatus(eq("qr-a"), any());
  }

  /**
   * 验证 BossAuthServiceImpl 的权限与租户隔离边界。
   */
  @Test
  void missingQrOwnerMustFailClosed() {
    AuthenticationScope.set("tenant-a", "user-a");
    BossCliService bossCli = mock(BossCliService.class);
    AuthStateRepository repository = mock(AuthStateRepository.class);
    when(repository.findQrSession("unknown")).thenReturn(null);
    BossAuthServiceImpl service = new BossAuthServiceImpl(bossCli, repository);

    assertThrows(IllegalArgumentException.class, () -> service.loginStatus("chat-a", "unknown"));
    verify(bossCli, never()).qrStatus(eq("unknown"), any());
  }

  /**
   * 验证 BossAuthServiceImpl 中凭据的权限与租户隔离边界。
   */
  @Test
  void qrLoggedInMustPersistReturnedCredentialForExactOwner() {
    AuthenticationScope.set("tenant-a", "user-a");
    BossCliService bossCli = mock(BossCliService.class);
    AuthStateRepository repository = mock(AuthStateRepository.class);
    when(repository.findQrSession("qr-a")).thenReturn(qrOwner("tenant-a", "user-a", "qr-a"));
    Map<String, Object> data = new LinkedHashMap<String, Object>();
    data.put("status", "logged_in");
    data.put("credential_json", "{\"cookies\":{\"wt2\":\"owner-a\"}}");
    when(bossCli.qrStatus("qr-a", "token-qr-a")).thenReturn(envelope(data));
    BossAuthServiceImpl service = new BossAuthServiceImpl(bossCli, repository);

    Map<String, Object> done = JSON.toMap(service.loginStatus("chat-a", "qr-a"));

    assertTrue(Boolean.TRUE.equals(done.get("ok")));
    Map<String, Object> otherEntry =
        JSON.toMap(service.loginStatus("settings", "qr-from-other-entry"));
    assertTrue(Boolean.TRUE.equals(otherEntry.get("ok")));
    verify(bossCli, times(1)).qrStatus("qr-a", "token-qr-a");
    verify(repository)
        .save(
            eq("tenant-a"),
            eq("user-a"),
            eq("jackwener/boss-cli"),
            eq("logged_in"),
            eq("{\"cookies\":{\"wt2\":\"owner-a\"}}"),
            any(Map.class));
    verify(repository).deleteQrSession("tenant-a", "user-a", "qr-a");
  }

  /**
   * 验证 BossAuthServiceImpl 中凭据的身份认证与会话边界。
   */
  @Test
  void qrLoggedInWithoutCredentialMustNotCreateFakeLoginMarker() {
    AuthenticationScope.set("tenant-a", "user-a");
    BossCliService bossCli = mock(BossCliService.class);
    AuthStateRepository repository = mock(AuthStateRepository.class);
    when(repository.findQrSession("qr-a")).thenReturn(qrOwner("tenant-a", "user-a", "qr-a"));
    Map<String, Object> data = new LinkedHashMap<String, Object>();
    data.put("status", "logged_in");
    when(bossCli.qrStatus("qr-a", "token-qr-a")).thenReturn(envelope(data));
    BossAuthServiceImpl service = new BossAuthServiceImpl(bossCli, repository);

    assertThrows(IllegalStateException.class, () -> service.loginStatus("chat-a", "qr-a"));
    verify(repository, never())
        .save(
            eq("tenant-a"),
            eq("user-a"),
            eq("jackwener/boss-cli"),
            eq("logged_in"),
            any(),
            any(Map.class));
  }

  /**
   * 验证 BossAuthServiceImpl 中工具的流式生命周期与中断边界。
   */
  @Test
  void qrCancelMustDeleteSessionOnlyAfterToolCancellationSucceeds() {
    AuthenticationScope.set("tenant-a", "user-a");
    BossCliService bossCli = mock(BossCliService.class);
    AuthStateRepository repository = mock(AuthStateRepository.class);
    when(repository.findQrSession("qr-a")).thenReturn(qrOwner("tenant-a", "user-a", "qr-a"));
    when(bossCli.qrCancel("qr-a", "token-qr-a")).thenReturn(new BossCliCancelResult());
    BossAuthServiceImpl service = new BossAuthServiceImpl(bossCli, repository);

    service.cancelLogin("chat-a", "qr-a");

    verify(bossCli).qrCancel("qr-a", "token-qr-a");
    verify(repository).deleteQrSession("tenant-a", "user-a", "qr-a");
  }

  /**
   * 验证 BossAuthServiceImpl 中会话的失败恢复、超时与降级边界。
   */
  @Test
  void qrCancelFailureMustPreserveSessionForRetry() {
    AuthenticationScope.set("tenant-a", "user-a");
    BossCliService bossCli = mock(BossCliService.class);
    AuthStateRepository repository = mock(AuthStateRepository.class);
    when(repository.findQrSession("qr-a")).thenReturn(qrOwner("tenant-a", "user-a", "qr-a"));
    when(bossCli.qrCancel("qr-a", "token-qr-a"))
        .thenThrow(new IllegalStateException("tool unavailable"));
    BossAuthServiceImpl service = new BossAuthServiceImpl(bossCli, repository);

    assertThrows(IllegalStateException.class, () -> service.cancelLogin("chat-a", "qr-a"));

    verify(repository, never()).deleteQrSession(any(), any(), any());
  }

  /**
   * 验证退出登录只清除当前属主的持久化凭据、二维码会话和认证缓存。
   */
  @Test
  void logoutMustClearCurrentOwnerCredentialAndAuthenticationCache() {
    AuthenticationScope.set("tenant-a", "user-a");
    BossCliService bossCli = mock(BossCliService.class);
    AuthStateRepository repository = mock(AuthStateRepository.class);
    when(bossCli.status()).thenReturn(status("logged_in", true), status("auth_required", false));
    BossAuthServiceImpl service = new BossAuthServiceImpl(bossCli, repository);

    assertTrue(Boolean.TRUE.equals(JSON.toMap(service.loginStatus("settings", null)).get("ok")));
    Map<String, Object> logout = JSON.toMap(service.logout());
    Map<String, Object> afterLogout = JSON.toMap(service.loginStatus("settings", null));

    assertEquals("logged_out", logout.get("status"));
    assertFalse(Boolean.TRUE.equals(afterLogout.get("ok")));
    verify(repository)
        .clearCredential(
            eq("tenant-a"),
            eq("user-a"),
            eq("jackwener/boss-cli"),
            eq("logged_out"),
            any(Map.class));
    verify(repository)
        .clearCredential(
            eq("tenant-a"), eq("user-a"), eq("boss-zhipin"), eq("logged_out"), any(Map.class));
    verify(repository).deleteQrSessionsForOwner("tenant-a", "user-a");
    verify(bossCli, times(2)).status();
  }

  /**
   * 验证二维码属主。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param qrSessionId 二维码会话标识
   * @return qr 属主
   */
  private Map<String, Object> qrOwner(String tenantId, String userId, String qrSessionId) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("tenantId", tenantId);
    row.put("userId", userId);
    row.put("qrSessionId", qrSessionId);
    row.put("toolSessionToken", "token-" + qrSessionId);
    row.put("toolSessionVersion", 1);
    row.put("expiresAt", Instant.now().plusSeconds(120));
    return row;
  }

  /**
   * 验证状态。
   *
   * @param status 状态
   * @param ok 是否成功
   * @return 执行状态
   */
  private BossCliStatusResult status(String status, boolean ok) {
    BossCliStatusResult data = new BossCliStatusResult();
    data.setOk(ok);
    data.setAuthenticated(ok);
    data.setStatus(status);
    return data;
  }

  /**
   * 验证响应封装。
   *
   * @param data 数据
   * @return 模拟下游响应
   */
  private BossCliQrResult envelope(Map<String, Object> data) {
    BossCliQrResult envelope = new BossCliQrResult();
    envelope.setOk(true);
    envelope.setData(JSON.toTree(data));
    return envelope;
  }
}
