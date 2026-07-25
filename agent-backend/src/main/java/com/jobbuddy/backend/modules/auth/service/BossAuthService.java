package com.jobbuddy.backend.modules.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobbuddy.backend.modules.auth.dto.response.BossLoginCancelResponse;
import com.jobbuddy.backend.modules.auth.dto.response.BossLoginQrResponse;
import com.jobbuddy.backend.modules.auth.dto.response.BossLoginStatusResponse;

/**
 * 管理用户级 Boss 认证生命周期且不暴露原始凭据。
 *
 * <p>实现可在内存短暂缓存解密凭据，但持久化状态必须加密保存于 Backend 认证存储。
 */
public interface BossAuthService {
  /**
   * 获取登录引导信息。
   *
   * @return 登录引导信息
   */
  BossLoginStatusResponse loginPrompt();

  /**
   * 启动二维码登录。
   *
   * @param sessionId 会话标识
   * @return 启动后的二维码登录
   */
  BossLoginQrResponse startQrLogin(String sessionId);

  /**
   * 获取登录状态。
   *
   * @param sessionId 会话标识
   * @param qrSessionIdOverride 指定的二维码会话标识
   * @return 登录状态
   */
  BossLoginStatusResponse loginStatus(String sessionId, String qrSessionIdOverride);

  /**
   * 取消登录。
   *
   * @param sessionId 会话标识
   * @param qrSessionIdOverride 指定的二维码会话标识
   * @return 登录取消结果
   */
  BossLoginCancelResponse cancelLogin(String sessionId, String qrSessionIdOverride);

  /**
   * 判断是否已登录。
   *
   * @param sessionId 会话标识
   * @return 是否已登录
   */
  boolean isLoggedIn(String sessionId);

  /**
   * 记录当前凭据。
   *
   * @param source 源数据
   */
  void rememberCurrentCredential(JsonNode source);

  /**
   * 工具服务报告登录失效时，同时清除持久化与内存状态。
   *
   * @param source 源数据
   */
  void markLoginInvalid(JsonNode source);

  /**
   * 校验可用登录态；不存在时抛出领域认证异常。
   *
   * @param sessionId 会话标识
   */
  void requireLoginOrThrow(String sessionId);
}
