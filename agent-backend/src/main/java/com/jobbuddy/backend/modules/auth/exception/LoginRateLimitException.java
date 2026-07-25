package com.jobbuddy.backend.modules.auth.exception;

/**
 * 携带有界重试等待时间的登录准入异常。
 */
public class LoginRateLimitException extends RuntimeException {
  private final long retryAfterSeconds;

  /**
   * 创建登录比例上限异常实例。
   *
   * @param retryAfterSeconds 重试后秒数
   */
  public LoginRateLimitException(long retryAfterSeconds) {
    super("登录尝试过于频繁，请稍后重试");
    this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
  }

  /**
   * 获取重试之后秒。
   *
   * @return 重试后秒数
   */
  public long getRetryAfterSeconds() {
    return retryAfterSeconds;
  }
}
