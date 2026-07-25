package com.jobbuddy.backend.modules.auth.exception;

/**
 * 已认证管理请求被领域策略拒绝时的授权异常。
 */
public class AuthorizationDeniedException extends RuntimeException {
  /**
   * 创建授权拒绝异常实例。
   *
   * @param message 消息内容
   */
  public AuthorizationDeniedException(String message) {
    super(message);
  }
}
