package com.jobbuddy.backend.modules.auth.dto.response;

import lombok.Data;

/**
 * 承载登录响应数据。
 */
@Data
public class LoginResponse {
  private String token;
  private String expiresAt;
  private CurrentUserResponse user;
}
