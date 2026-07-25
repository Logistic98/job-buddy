package com.jobbuddy.backend.modules.auth.service.impl;

import com.jobbuddy.backend.modules.auth.exception.LoginRateLimitException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 限制登录尝试与并发密码哈希。Redis 负责跨实例状态，本地有界映射在 Redis 故障时维持保护。
 */
@Component
public class LoginAttemptGuard {
  private static final Logger LOG = LoggerFactory.getLogger(LoginAttemptGuard.class);
  private static final int LOCAL_MAX_KEYS = 20_000;
  private static final long RATE_WINDOW_SECONDS = 300L;
  private static final int MAX_ATTEMPTS_PER_ACCOUNT = 8;
  private static final int MAX_ATTEMPTS_PER_SOURCE = 60;
  private static final int MAX_CONCURRENT_PASSWORD_HASHES = 8;
  private static final DefaultRedisScript<Long> INCREMENT_SCRIPT =
      new DefaultRedisScript<Long>(
          "local n=redis.call('INCR',KEYS[1]);"
              + "if n==1 then redis.call('PEXPIRE',KEYS[1],ARGV[1]); end;"
              + "return n;",
          Long.class);

  private final StringRedisTemplate redis;
  private final Clock clock;
  private final long rateWindowSeconds;
  private final int maxAttemptsPerAccount;
  private final int maxAttemptsPerSource;
  private final Semaphore passwordHashBudget;
  private final Map<String, LocalWindow> localWindows =
      new ConcurrentHashMap<String, LocalWindow>();
  private final AtomicBoolean fallbackWarningLogged = new AtomicBoolean(false);

  /**
   * 创建登录尝试守卫实例。
   *
   * @param redisProvider Redis 提供器
   */
  @Autowired
  public LoginAttemptGuard(ObjectProvider<StringRedisTemplate> redisProvider) {
    this(
        redisProvider.getIfAvailable(),
        Clock.systemUTC(),
        RATE_WINDOW_SECONDS,
        MAX_ATTEMPTS_PER_ACCOUNT,
        MAX_ATTEMPTS_PER_SOURCE,
        MAX_CONCURRENT_PASSWORD_HASHES);
  }

  /**
   * 创建登录尝试守卫实例。
   *
   * @param redis Redis 操作客户端
   * @param clock 时钟
   * @param rateWindowSeconds 限速窗口秒数
   * @param maxAttemptsPerAccount 单账号最大尝试次数
   * @param maxAttemptsPerSource 单来源最大尝试次数
   * @param maxConcurrentPasswordHashes 最大并发密码哈希数
   */
  LoginAttemptGuard(
      StringRedisTemplate redis,
      Clock clock,
      long rateWindowSeconds,
      int maxAttemptsPerAccount,
      int maxAttemptsPerSource,
      int maxConcurrentPasswordHashes) {
    this.redis = redis;
    this.clock = clock;
    this.rateWindowSeconds = Math.max(1L, rateWindowSeconds);
    this.maxAttemptsPerAccount = Math.max(1, maxAttemptsPerAccount);
    this.maxAttemptsPerSource = Math.max(1, maxAttemptsPerSource);
    this.passwordHashBudget = new Semaphore(Math.max(1, maxConcurrentPasswordHashes), true);
  }

  /**
   * 申请流式响应执行许可。
   *
   * @param account 登录账号
   * @param source 源数据
   * @return 并发许可
   */
  public AttemptLease acquire(String account, String source) {
    long windowMillis = rateWindowSeconds * 1_000L;
    long accountCount = increment("account", normalizeAccount(account), windowMillis);
    long sourceCount = increment("source", normalizeSource(source), windowMillis);
    if (accountCount > maxAttemptsPerAccount || sourceCount > maxAttemptsPerSource) {
      throw new LoginRateLimitException(rateWindowSeconds);
    }
    if (!passwordHashBudget.tryAcquire()) {
      throw new LoginRateLimitException(1L);
    }
    return new AttemptLease(passwordHashBudget);
  }

  /**
   * 记录成功结果。
   *
   * @param account 登录账号
   */
  public void recordSuccess(String account) {
    String key = key("account", normalizeAccount(account));
    localWindows.remove(key);
    if (redis != null) {
      try {
        redis.delete(key);
      } catch (RuntimeException exception) {
        logFallback(exception);
      }
    }
  }

  /**
   * 增加登录失败计数。
   *
   * @param dimension 统计维度
   * @param value 输入值
   * @param windowMillis 时间窗口毫秒数
   * @return 递增后的值
   */
  private long increment(String dimension, String value, long windowMillis) {
    String key = key(dimension, value);
    if (redis != null) {
      try {
        Long count =
            redis.execute(
                INCREMENT_SCRIPT, Collections.singletonList(key), String.valueOf(windowMillis));
        if (count != null) return count.longValue();
      } catch (RuntimeException exception) {
        logFallback(exception);
      }
    }
    return incrementLocal(key, windowMillis);
  }

  /**
   * 增加本地失败计数。
   *
   * @param key 业务键
   * @param windowMillis 时间窗口毫秒数
   * @return 本地递增后的值
   */
  private long incrementLocal(String key, long windowMillis) {
    long now = clock.millis();
    if (localWindows.size() >= LOCAL_MAX_KEYS) {
      localWindows.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis <= now);
      if (localWindows.size() >= LOCAL_MAX_KEYS && !localWindows.containsKey(key)) {
        throw new LoginRateLimitException(Math.max(1L, windowMillis / 1_000L));
      }
    }
    LocalWindow value =
        localWindows.compute(
            key,
            (ignored, current) -> {
              if (current == null || current.expiresAtMillis <= now) {
                return new LocalWindow(1L, now + windowMillis);
              }
              return new LocalWindow(current.count + 1L, current.expiresAtMillis);
            });
    return value.count;
  }

  /**
   * 记录登录限流降级结果。
   *
   * @param exception 异常
   */
  private void logFallback(RuntimeException exception) {
    if (fallbackWarningLogged.compareAndSet(false, true)) {
      LOG.warn("登录限流 Redis 不可用，已切换到进程内有界保护: {}", exception.getClass().getSimpleName());
    }
  }

  /**
   * 获取键。
   *
   * @param dimension 统计维度
   * @param value 输入值
   * @return 业务键
   */
  private String key(String dimension, String value) {
    return "job-buddy:auth:login:" + dimension + ":" + sha256(value);
  }

  /**
   * 规范化登录账号。
   *
   * @param value 输入值
   * @return 规范化后的登录账号
   */
  private String normalizeAccount(String value) {
    return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
  }

  /**
   * 规范化来源。
   *
   * @param value 输入值
   * @return 规范化后的来源
   */
  private String normalizeSource(String value) {
    String normalized = value == null ? "" : value.trim();
    return normalized.isEmpty() ? "unknown" : normalized;
  }

  /**
   * 计算 SHA-256 摘要。
   *
   * @param value 输入值
   * @return SHA-256 摘要
   */
  private String sha256(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 不可用", exception);
    }
  }

  /**
   * 定义尝试许可凭证。
   */
  static final class AttemptLease implements AutoCloseable {
    private final Semaphore semaphore;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 创建尝试许可凭证实例。
     *
     * @param semaphore 并发信号量
     */
    private AttemptLease(Semaphore semaphore) {
      this.semaphore = semaphore;
    }

    /**
     * 关闭当前资源。
     */
    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) semaphore.release();
    }
  }

  /**
   * 定义本地窗口。
   */
  private static final class LocalWindow {
    private final long count;
    private final long expiresAtMillis;

    /**
     * 创建本地窗口实例。
     *
     * @param count 数量
     * @param expiresAtMillis 过期时间毫秒数
     */
    private LocalWindow(long count, long expiresAtMillis) {
      this.count = count;
      this.expiresAtMillis = expiresAtMillis;
    }
  }
}
