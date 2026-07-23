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
 * Bounds login attempts and concurrent password hashes. Redis is authoritative across instances;
 * the bounded local map preserves protection during a Redis outage.
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

  private void logFallback(RuntimeException exception) {
    if (fallbackWarningLogged.compareAndSet(false, true)) {
      LOG.warn("登录限流 Redis 不可用，已切换到进程内有界保护: {}", exception.getClass().getSimpleName());
    }
  }

  private String key(String dimension, String value) {
    return "job-buddy:auth:login:" + dimension + ":" + sha256(value);
  }

  private String normalizeAccount(String value) {
    return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
  }

  private String normalizeSource(String value) {
    String normalized = value == null ? "" : value.trim();
    return normalized.isEmpty() ? "unknown" : normalized;
  }

  private String sha256(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 不可用", exception);
    }
  }

  static final class AttemptLease implements AutoCloseable {
    private final Semaphore semaphore;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private AttemptLease(Semaphore semaphore) {
      this.semaphore = semaphore;
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) semaphore.release();
    }
  }

  private static final class LocalWindow {
    private final long count;
    private final long expiresAtMillis;

    private LocalWindow(long count, long expiresAtMillis) {
      this.count = count;
      this.expiresAtMillis = expiresAtMillis;
    }
  }
}
