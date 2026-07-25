package com.jobbuddy.backend.modules.auth.service.impl;

import com.jobbuddy.backend.common.security.AuthenticatedMenu;
import com.jobbuddy.backend.common.security.AuthenticatedUser;
import com.jobbuddy.backend.modules.auth.dto.response.CurrentUserResponse;
import com.jobbuddy.backend.modules.auth.dto.response.LoginResponse;
import com.jobbuddy.backend.modules.auth.exception.InvalidCredentialsException;
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

/**
 * 校验哈希凭据并管理有时效的不透明会话令牌。
 *
 * <p>失败登录受限流保护；会话查询实时重建角色、权限和菜单，不信任令牌携带的授权状态。
 */
@Service
public class UserLoginServiceImpl implements UserLoginService {
  private static final long SESSION_CACHE_SECONDS = 60L;
  private static final int SESSION_CACHE_MAX_ENTRIES = 4096;

  private final UserAuthRepository repository;
  private final SecureRandom secureRandom = new SecureRandom();
  private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
  private final Map<String, CachedSession> sessionCache =
      new ConcurrentHashMap<String, CachedSession>();
  private final LoginAttemptGuard loginAttemptGuard;
  private final String dummyPasswordHash;

  /**
   * 创建用户登录服务实例。
   *
   * @param repository 存储访问
   * @param loginAttemptGuard 登录尝试守卫
   */
  public UserLoginServiceImpl(UserAuthRepository repository, LoginAttemptGuard loginAttemptGuard) {
    this.repository = repository;
    this.loginAttemptGuard = loginAttemptGuard;
    this.dummyPasswordHash = passwordEncoder.encode("job-buddy-dummy-password");
  }

  /**
   * 执行用户登录。
   *
   * @param username 用户名
   * @param password 密码
   * @param source 源数据
   * @return 登录结果
   */
  @Override
  public LoginResponse login(String username, String password, String source) {
    String safeUsername = username == null ? "" : username.trim();
    String safePassword = password == null ? "" : password;
    if (safeUsername.isEmpty() || safePassword.isEmpty()) {
      throw new InvalidCredentialsException();
    }
    try (LoginAttemptGuard.AttemptLease ignored = loginAttemptGuard.acquire(safeUsername, source)) {
      Map<String, Object> user = repository.findUserByUsername(safeUsername);
      String passwordHash =
          user == null ? dummyPasswordHash : String.valueOf(user.get("passwordHash"));
      boolean passwordMatches = passwordEncoder.matches(safePassword, passwordHash);
      if (user == null || !Boolean.TRUE.equals(user.get("enabled")) || !passwordMatches) {
        throw new InvalidCredentialsException();
      }
      loginAttemptGuard.recordSuccess(safeUsername);
      return createSession(user);
    }
  }

  /**
   * 创建会话。
   *
   * @param user 用户
   * @return 创建后的会话
   */
  private LoginResponse createSession(Map<String, Object> user) {
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

  /**
   * 获取当前用户。
   *
   * @param token 认证令牌
   * @return 当前用户
   */
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

  /**
   * 执行用户退出登录。
   *
   * @param token 认证令牌
   */
  @Override
  public void logout(String token) {
    if (token == null || token.trim().isEmpty()) return;
    String safeToken = token.trim();
    sessionCache.remove(safeToken);
    repository.deleteSession(safeToken);
  }

  /**
   * 使用户会话失效。
   *
   * @param userId 用户标识
   */
  @Override
  public void invalidateUserSessions(String userId) {
    if (userId == null || userId.trim().isEmpty()) return;
    sessionCache.entrySet().removeIf(entry -> userId.equals(entry.getValue().user.getUserId()));
    repository.deleteSessionsByUserId(userId);
  }

  /**
   * 处理缓存会话。
   *
   * @param token 认证令牌
   * @param user 用户
   * @param expiresAt 过期时间
   */
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

  /**
   * 定义缓存会话。
   */
  private static final class CachedSession {
    private final AuthenticatedUser user;
    private final Instant cacheUntil;

    /**
     * 创建缓存会话实例。
     *
     * @param user 用户
     * @param cacheUntil 缓存截止时间
     */
    private CachedSession(AuthenticatedUser user, Instant cacheUntil) {
      this.user = user;
      this.cacheUntil = cacheUntil;
    }

    /**
     * 判断会话在指定时刻是否有效。
     *
     * @param now 当前时间
     * @return 会话在指定时刻是否有效是否成立
     */
    private boolean isUsableAt(Instant now) {
      return user != null && cacheUntil != null && cacheUntil.isAfter(now);
    }
  }

  /**
   * 构建脱敏用户信息。
   *
   * @param user 用户
   * @return 公开用户信息
   */
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

  /**
   * 获取已认证菜单。
   *
   * @param rows 查询行列表
   * @return 已认证菜单
   */
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

  /**
   * 获取字符串或空值。
   *
   * @param value 输入值
   * @return 字符串或空值
   */
  private String stringOrNull(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  /**
   * 生成令牌。
   *
   * @return 令牌
   */
  private String newToken() {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    StringBuilder builder = new StringBuilder();
    for (byte b : bytes) builder.append(String.format("%02x", b & 0xff));
    return builder.toString();
  }

  /**
   * 转换为时间点。
   *
   * @param value 输入值
   * @return 转换后的时间点
   */
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
