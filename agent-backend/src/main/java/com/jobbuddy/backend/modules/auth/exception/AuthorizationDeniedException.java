package com.jobbuddy.backend.modules.auth.exception;

/** Authorization failure after an authenticated management request reaches domain policy. */
public class AuthorizationDeniedException extends RuntimeException {
  public AuthorizationDeniedException(String message) {
    super(message);
  }
}
