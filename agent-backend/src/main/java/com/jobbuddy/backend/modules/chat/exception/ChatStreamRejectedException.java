package com.jobbuddy.backend.modules.chat.exception;

/** Raised before the SSE response is committed when stream capacity is exhausted. */
public class ChatStreamRejectedException extends RuntimeException {
  private final boolean retryable;

  public ChatStreamRejectedException(String message, boolean retryable) {
    super(message);
    this.retryable = retryable;
  }

  public boolean isRetryable() {
    return retryable;
  }
}
