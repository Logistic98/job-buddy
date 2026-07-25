package com.jobbuddy.backend.modules.auth.dto.request;

import lombok.Data;

/**
 * 承载密码重置请求参数。
 */
@Data
public class PasswordResetRequest {
  private String password;
}
