package com.jobbuddy.backend.modules.auth.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 表示 Boss 认证必需异常。
 */
public class BossAuthRequiredException extends RuntimeException {
  private final Map<String, Object> authData;

  /**
   * 创建 Boss 认证必需异常实例。
   *
   * @param message 消息内容
   * @param authData 认证数据
   */
  public BossAuthRequiredException(String message, Map<String, Object> authData) {
    super(message);
    this.authData =
        authData == null
            ? Collections.<String, Object>emptyMap()
            : new LinkedHashMap<String, Object>(authData);
  }

  /**
   * 获取认证数据。
   *
   * @return 认证数据
   */
  public Map<String, Object> getAuthData() {
    return authData;
  }
}
