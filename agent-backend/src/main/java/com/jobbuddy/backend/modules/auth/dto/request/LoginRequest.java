package com.jobbuddy.backend.modules.auth.dto.request;

import lombok.Data;

/**
 * 承载登录请求参数。
 */
@Data
public class LoginRequest {
  private String username;
  private String password;
}
