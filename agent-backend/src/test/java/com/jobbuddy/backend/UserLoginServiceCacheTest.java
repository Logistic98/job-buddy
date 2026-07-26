package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.security.AuthenticatedUser;
import com.jobbuddy.backend.modules.auth.dto.response.LoginResponse;
import com.jobbuddy.backend.modules.auth.repository.UserAuthRepository;
import com.jobbuddy.backend.modules.auth.service.UserLoginService;
import com.jobbuddy.backend.modules.auth.service.impl.LoginAttemptGuard;
import com.jobbuddy.backend.modules.auth.service.impl.UserLoginServiceImpl;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 验证 UserLoginServiceCache 的核心行为、异常路径与边界条件。
 */
class UserLoginServiceCacheTest {

  /**
   * 验证 UserLoginServiceCache 中用户的权限与租户隔离边界。
   */
  @Test
  void loginUsesGlobalUsernameAndLoadsDynamicRbacContext() {
    UserAuthRepository repository = mock(UserAuthRepository.class);
    Map<String, Object> row = userRow();
    row.put("passwordHash", new BCryptPasswordEncoder().encode("secret123"));
    when(repository.findUserByUsername("admin")).thenReturn(row);
    when(repository.findRoles("admin")).thenReturn(Arrays.asList("platform-manager"));
    when(repository.findPermissions("admin")).thenReturn(Arrays.asList("users:manage"));
    when(repository.findMenus("admin")).thenReturn(Collections.<Map<String, Object>>emptyList());
    UserLoginService service = new UserLoginServiceImpl(repository, loginGuard());

    LoginResponse response = service.login("admin", "secret123", "127.0.0.1");

    assertEquals(Arrays.asList("platform-manager"), response.getUser().getRoles());
    assertEquals(Arrays.asList("users:manage"), response.getUser().getPermissions());
    verify(repository).findUserByUsername("admin");
    verify(repository).findRoles("admin");
    verify(repository).findPermissions("admin");
  }

  /**
   * 验证 UserLoginServiceCache 中登录的持久化与状态变更规则。
   */
  @Test
  void loginAcceptsFlywaySeededDefaultPassword() {
    UserAuthRepository repository = mock(UserAuthRepository.class);
    Map<String, Object> row = userRow();
    row.put("passwordHash", "$2y$10$/EhR7XPpYytk1JNM5FgdN.jq0zjp4AnUU4ej4VtpDPrF0aa5TxTF6");
    when(repository.findUserByUsername("admin")).thenReturn(row);
    when(repository.findRoles("admin")).thenReturn(Arrays.asList("admin"));
    when(repository.findPermissions("admin")).thenReturn(Collections.<String>emptyList());
    when(repository.findMenus("admin")).thenReturn(Collections.<Map<String, Object>>emptyList());
    UserLoginService service = new UserLoginServiceImpl(repository, loginGuard());

    LoginResponse response = service.login("admin", "12345678", "127.0.0.1");

    assertEquals("admin", response.getUser().getUsername());
    verify(repository)
        .saveSession(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq("admin"),
            org.mockito.ArgumentMatchers.any(Instant.class));
  }

  /**
   * 验证 UserLoginServiceCache 中记忆的去重与幂等边界。
   */
  @Test
  void repeatedTokenValidationShouldUseShortLivedMemoryCache() {
    UserAuthRepository repository = mock(UserAuthRepository.class);
    when(repository.findUserByToken("token-1")).thenReturn(userRow());
    UserLoginService service = new UserLoginServiceImpl(repository, loginGuard());

    AuthenticatedUser first = service.currentUser("token-1");
    AuthenticatedUser second = service.currentUser("token-1");

    assertEquals("admin", first.getUserId());
    assertEquals("admin", second.getUserId());
    verify(repository, times(1)).findUserByToken("token-1");
    verify(repository, never()).deleteExpiredSessions();
  }

  /**
   * 验证 UserLoginServiceCache 中缓存的缓存一致性规则。
   */
  @Test
  void logoutShouldEvictCachedSessionImmediately() {
    UserAuthRepository repository = mock(UserAuthRepository.class);
    when(repository.findUserByToken("token-2")).thenReturn(userRow()).thenReturn(null);
    UserLoginService service = new UserLoginServiceImpl(repository, loginGuard());

    service.currentUser("token-2");
    service.logout("token-2");

    assertNull(service.currentUser("token-2"));
    verify(repository, times(2)).findUserByToken("token-2");
    verify(repository).deleteSession("token-2");
  }

  /**
   * 验证授权元数据变更只刷新缓存，不撤销仍有效的数据库会话。
   */
  @Test
  void authorizationCacheEvictionShouldKeepSessionAndReloadCurrentUser() {
    UserAuthRepository repository = mock(UserAuthRepository.class);
    when(repository.findUserByToken("token-3")).thenReturn(userRow());
    when(repository.findPermissions("admin"))
        .thenReturn(Arrays.asList("menus:manage"))
        .thenReturn(Arrays.asList("menus:manage", "jobs:use"));
    UserLoginService service = new UserLoginServiceImpl(repository, loginGuard());

    AuthenticatedUser before = service.currentUser("token-3");
    service.evictUserSessionCache("admin");
    AuthenticatedUser after = service.currentUser("token-3");

    assertEquals(Arrays.asList("menus:manage"), before.getPermissions().stream().toList());
    assertEquals(
        Arrays.asList("menus:manage", "jobs:use"), after.getPermissions().stream().toList());
    verify(repository, times(2)).findUserByToken("token-3");
    verify(repository, never()).deleteSessionsByUserId("admin");
  }

  /**
   * 验证用户记录。
   *
   * @return 当前认证用户 Row
   */
  private Map<String, Object> userRow() {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("userId", "admin");
    row.put("username", "admin");
    row.put("displayName", "管理员");
    row.put("role", "admin");
    row.put("enabled", true);
    row.put("expiresAt", Instant.now().plusSeconds(3600).toString());
    return row;
  }

  /**
   * 验证登录守卫。
   *
   * @return 登录尝试守卫
   */
  @SuppressWarnings("unchecked")
  private LoginAttemptGuard loginGuard() {
    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    return new LoginAttemptGuard(provider);
  }
}
