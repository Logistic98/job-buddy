package com.jobbuddy.backend.modules.auth.exception;

/** Stable authentication failure that does not reveal whether an account exists. */
public class InvalidCredentialsException extends RuntimeException {
  public InvalidCredentialsException() {
    super("用户名或密码错误");
  }
}
