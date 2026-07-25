package com.jobbuddy.backend.modules.auth.dto.internal;

import lombok.Data;

/**
 * 定义 Boss CLI 取消结果。
 */
@Data
public class BossCliCancelResult {
  private Boolean ok;
  private String status;
}
