package com.jobbuddy.backend.modules.auth.exception;

/** Login admission failure with a bounded retry delay. */
public class LoginRateLimitException extends RuntimeException {
  private final long retryAfterSeconds;

  public LoginRateLimitException(long retryAfterSeconds) {
    super("登录尝试过于频繁，请稍后重试");
    this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
  }

  public long getRetryAfterSeconds() {
    return retryAfterSeconds;
  }
}
