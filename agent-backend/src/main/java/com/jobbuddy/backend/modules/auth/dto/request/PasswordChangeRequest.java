package com.jobbuddy.backend.modules.auth.dto.request;

import lombok.Data;

/**
 * 承载密码修改请求参数。
 */
@Data
public class PasswordChangeRequest {
  private String oldPassword;
  private String newPassword;
}
