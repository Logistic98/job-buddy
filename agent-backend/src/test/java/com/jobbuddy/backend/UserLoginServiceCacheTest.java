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
import com.jobbuddy.backend.modules.auth.service.impl.UserLoginServiceImpl;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class UserLoginServiceCacheTest {

  @Test
  void loginUsesGlobalUsernameAndLoadsDynamicRbacContext() {
    UserAuthRepository repository = mock(UserAuthRepository.class);
    Map<String, Object> row = userRow();
    row.put("passwordHash", new BCryptPasswordEncoder().encode("secret123"));
    when(repository.findUserByUsername("admin")).thenReturn(row);
    when(repository.findRoles("admin")).thenReturn(Arrays.asList("platform-manager"));
    when(repository.findPermissions("admin")).thenReturn(Arrays.asList("users:manage"));
    when(repository.findMenus("admin")).thenReturn(Collections.<Map<String, Object>>emptyList());
    UserLoginService service = new UserLoginServiceImpl(repository);

    LoginResponse response = service.login("admin", "secret123");

    assertEquals(Arrays.asList("platform-manager"), response.getUser().getRoles());
    assertEquals(Arrays.asList("users:manage"), response.getUser().getPermissions());
    verify(repository).findUserByUsername("admin");
    verify(repository).findRoles("admin");
    verify(repository).findPermissions("admin");
  }

  @Test
  void repeatedTokenValidationShouldUseShortLivedMemoryCache() {
    UserAuthRepository repository = mock(UserAuthRepository.class);
    when(repository.findUserByToken("token-1")).thenReturn(userRow());
    UserLoginService service = new UserLoginServiceImpl(repository);

    AuthenticatedUser first = service.currentUser("token-1");
    AuthenticatedUser second = service.currentUser("token-1");

    assertEquals("admin", first.getUserId());
    assertEquals("admin", second.getUserId());
    verify(repository, times(1)).findUserByToken("token-1");
    verify(repository, never()).deleteExpiredSessions();
    verify(repository, never()).touchSession("token-1");
  }

  @Test
  void logoutShouldEvictCachedSessionImmediately() {
    UserAuthRepository repository = mock(UserAuthRepository.class);
    when(repository.findUserByToken("token-2")).thenReturn(userRow()).thenReturn(null);
    UserLoginService service = new UserLoginServiceImpl(repository);

    service.currentUser("token-2");
    service.logout("token-2");

    assertNull(service.currentUser("token-2"));
    verify(repository, times(2)).findUserByToken("token-2");
    verify(repository).deleteSession("token-2");
  }

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
}
