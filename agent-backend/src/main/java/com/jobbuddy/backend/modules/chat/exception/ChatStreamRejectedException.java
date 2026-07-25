package com.jobbuddy.backend.modules.chat.exception;

/**
 * SSE 响应提交前因流容量耗尽而抛出的准入异常。
 */
public class ChatStreamRejectedException extends RuntimeException {
  private final boolean retryable;

  /**
   * 创建对话流式响应被拒绝异常实例。
   *
   * @param message 消息内容
   * @param retryable 是否允许重试
   */
  public ChatStreamRejectedException(String message, boolean retryable) {
    super(message);
    this.retryable = retryable;
  }

  /**
   * 判断是否可重试。
   *
   * @return 是否允许重试
   */
  public boolean isRetryable() {
    return retryable;
  }
}
