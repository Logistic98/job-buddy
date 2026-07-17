package com.jobbuddy.backend.modules.auth.dto.request;

import lombok.Data;

@Data
public class PasswordResetRequest {
  private String password;
}
