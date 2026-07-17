package com.jobbuddy.backend.modules.auth.dto.response;

import lombok.Data;

@Data
public class LoginResponse {
  private String token;
  private String expiresAt;
  private CurrentUserResponse user;
}
