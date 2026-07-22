package com.jobbuddy.backend.modules.auth.service.impl;

import com.jobbuddy.backend.common.security.AuthenticatedMenu;
import com.jobbuddy.backend.common.security.AuthenticatedUser;
import com.jobbuddy.backend.modules.auth.dto.response.CurrentUserResponse;
import com.jobbuddy.backend.modules.auth.dto.response.LoginResponse;
import com.jobbuddy.backend.modules.auth.repository.UserAuthRepository;
import com.jobbuddy.backend.modules.auth.service.UserLoginService;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserLoginServiceImpl implements UserLoginService {
  private static final long SESSION_CACHE_SECONDS = 60L;
  private static final int SESSION_CACHE_MAX_ENTRIES = 4096;

  private final UserAuthRepository repository;
  private final SecureRandom secureRandom = new SecureRandom();
  private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
  private final Map<String, CachedSession> sessionCache =
      new ConcurrentHashMap<String, CachedSession>();

  public UserLoginServiceImpl(UserAuthRepository repository) {
    this.repository = repository;
  }

  @Override
  public LoginResponse login(String username, String password) {
    String safeUsername = username == null ? "" : username.trim();
    String safePassword = password == null ? "" : password;
    if (safeUsername.isEmpty() || safePassword.isEmpty()) {
      throw new IllegalArgumentException("请输入用户名和密码");
    }
    Map<String, Object> user = repository.findUserByUsername(safeUsername);
    if (user == null || !Boolean.TRUE.equals(user.get("enabled"))) {
      throw new IllegalArgumentException("用户名或密码错误");
    }
    String passwordHash = String.valueOf(user.get("passwordHash"));
    if (!passwordEncoder.matches(safePassword, passwordHash)) {
      throw new IllegalArgumentException("用户名或密码错误");
    }
    repository.deleteExpiredSessions();
    String token = newToken();
    Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
    repository.saveSession(token, String.valueOf(user.get("userId")), expiresAt);
    AuthenticatedUser authenticatedUser = publicUser(user);
    cacheSession(token, authenticatedUser, expiresAt);
    LoginResponse result = new LoginResponse();
    result.setToken(token);
    result.setExpiresAt(expiresAt.toString());
    result.setUser(CurrentUserResponse.from(authenticatedUser));
    return result;
  }

  @Override
  public AuthenticatedUser currentUser(String token) {
    if (token == null || token.trim().isEmpty()) return null;
    String safeToken = token.trim();
    Instant now = Instant.now();
    CachedSession cached = sessionCache.get(safeToken);
    if (cached != null && cached.isUsableAt(now)) return cached.user;
    if (cached != null) sessionCache.remove(safeToken, cached);

    // 会话查询本身已关联用户并返回 expires_at。不要在每个业务请求前清理全表、
    // 再额外 touch 一次远程数据库；过期清理在登录时执行，热点校验由短 TTL 内存缓存承担。
    Map<String, Object> user = repository.findUserByToken(safeToken);
    if (user == null || !Boolean.TRUE.equals(user.get("enabled"))) return null;
    Instant expiresAt = toInstant(user.get("expiresAt"));
    if (expiresAt != null && expiresAt.isBefore(now)) return null;
    AuthenticatedUser authenticatedUser = publicUser(user);
    cacheSession(safeToken, authenticatedUser, expiresAt);
    return authenticatedUser;
  }

  @Override
  public void logout(String token) {
    if (token == null || token.trim().isEmpty()) return;
    String safeToken = token.trim();
    sessionCache.remove(safeToken);
    repository.deleteSession(safeToken);
  }

  @Override
  public void invalidateUserSessions(String userId) {
    if (userId == null || userId.trim().isEmpty()) return;
    sessionCache.entrySet().removeIf(entry -> userId.equals(entry.getValue().user.getUserId()));
    repository.deleteSessionsByUserId(userId);
  }

  private void cacheSession(String token, AuthenticatedUser user, Instant expiresAt) {
    Instant cacheUntil = Instant.now().plus(SESSION_CACHE_SECONDS, ChronoUnit.SECONDS);
    if (expiresAt != null && expiresAt.isBefore(cacheUntil)) cacheUntil = expiresAt;
    if (sessionCache.size() >= SESSION_CACHE_MAX_ENTRIES) {
      Instant now = Instant.now();
      sessionCache.entrySet().removeIf(entry -> !entry.getValue().isUsableAt(now));
      if (sessionCache.size() >= SESSION_CACHE_MAX_ENTRIES) sessionCache.clear();
    }
    sessionCache.put(token, new CachedSession(user, cacheUntil));
  }

  private static final class CachedSession {
    private final AuthenticatedUser user;
    private final Instant cacheUntil;

    private CachedSession(AuthenticatedUser user, Instant cacheUntil) {
      this.user = user;
      this.cacheUntil = cacheUntil;
    }

    private boolean isUsableAt(Instant now) {
      return user != null && cacheUntil != null && cacheUntil.isAfter(now);
    }
  }

  private AuthenticatedUser publicUser(Map<String, Object> user) {
    String userId = stringOrNull(user.get("userId"));
    return new AuthenticatedUser(
        userId,
        stringOrNull(user.get("username")),
        stringOrNull(user.get("displayName")),
        stringOrNull(user.get("role")),
        stringOrNull(user.get("tenantId")),
        stringOrNull(user.get("tenantCode")),
        new LinkedHashSet<String>(repository.findRoles(userId)),
        new LinkedHashSet<String>(repository.findPermissions(userId)),
        authenticatedMenus(repository.findMenus(userId)));
  }

  private List<AuthenticatedMenu> authenticatedMenus(List<Map<String, Object>> rows) {
    List<AuthenticatedMenu> result = new ArrayList<AuthenticatedMenu>();
    for (Map<String, Object> row : rows) {
      Object order = row.get("displayOrder");
      int displayOrder = order instanceof Number ? ((Number) order).intValue() : 0;
      result.add(
          new AuthenticatedMenu(
              stringOrNull(row.get("menuId")),
              stringOrNull(row.get("parentId")),
              stringOrNull(row.get("menuCode")),
              stringOrNull(row.get("menuName")),
              stringOrNull(row.get("menuType")),
              stringOrNull(row.get("routePath")),
              stringOrNull(row.get("componentKey")),
              stringOrNull(row.get("externalUrl")),
              stringOrNull(row.get("iconKey")),
              stringOrNull(row.get("permissionCode")),
              displayOrder));
    }
    return result;
  }

  private String stringOrNull(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private String newToken() {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    StringBuilder builder = new StringBuilder();
    for (byte b : bytes) builder.append(String.format("%02x", b & 0xff));
    return builder.toString();
  }

  private Instant toInstant(Object value) {
    if (value instanceof Instant) return (Instant) value;
    if (value instanceof java.sql.Timestamp) return ((java.sql.Timestamp) value).toInstant();
    if (value instanceof java.util.Date) return ((java.util.Date) value).toInstant();
    if (value == null) return null;
    try {
      return Instant.parse(String.valueOf(value));
    } catch (Exception e) {
      return null;
    }
  }
}
