package com.jobbuddy.backend.modules.auth.exception;

/**
 * 不泄露账号是否存在的稳定认证异常。
 */
public class InvalidCredentialsException extends RuntimeException {
  /**
   * 创建无效凭据异常实例。
   */
  public InvalidCredentialsException() {
    super("用户名或密码错误");
  }
}
