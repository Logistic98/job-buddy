package com.jobbuddy.backend.modules.auth.mapper;

import java.time.Instant;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 映射认证状态数据记录。
 */
public interface AuthStateMapper {
  /**
   * 按提供方查询认证状态。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param provider 提供器
   * @return 通过提供器
   */
  Map<String, Object> findByProvider(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("provider") String provider);

  /**
   * 统计按提供方。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param provider 提供器
   * @return 统计数量
   */
  int countByProvider(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("provider") String provider);

  /**
   * 新增状态。
   *
   * @param state 状态
   * @return 状态
   */
  int insertState(@Param("state") Map<String, Object> state);

  /**
   * 更新状态。
   *
   * @param state 状态
   * @return 状态
   */
  int updateState(@Param("state") Map<String, Object> state);

  /**
   * 清除指定提供方的持久化凭据。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param provider 提供器
   * @param status 状态
   * @param metadataJson 元数据 JSON
   * @param updatedAt 更新时间
   * @return 更新记录数
   */
  int clearCredential(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("provider") String provider,
      @Param("status") String status,
      @Param("metadataJson") String metadataJson,
      @Param("updatedAt") Instant updatedAt);

  /**
   * 新增或更新二维码会话。
   *
   * @param state 状态
   * @return 二维码会话
   */
  int upsertQrSession(@Param("state") Map<String, Object> state);

  /**
   * 更新二维码会话令牌。
   *
   * @param qrSessionId 二维码会话标识
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param toolSessionToken 工具会话令牌
   * @param currentToolSessionVersion 当前工具会话版本
   * @param toolSessionVersion 工具会话版本
   * @param updatedAt 更新时间
   * @return 二维码会话 Token
   */
  int updateQrSessionToken(
      @Param("qrSessionId") String qrSessionId,
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("toolSessionToken") String toolSessionToken,
      @Param("currentToolSessionVersion") int currentToolSessionVersion,
      @Param("toolSessionVersion") int toolSessionVersion,
      @Param("updatedAt") Instant updatedAt);

  /**
   * 查找二维码会话。
   *
   * @param qrSessionId 二维码会话标识
   * @return 二维码会话
   */
  Map<String, Object> findQrSession(@Param("qrSessionId") String qrSessionId);

  /**
   * 查找活动二维码会话。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param now 当前时间
   * @return 活动二维码会话
   */
  Map<String, Object> findActiveQrSession(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("now") Instant now);

  /**
   * 按对话标识查询二维码会话。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param chatSessionId 对话会话标识
   * @param now 当前时间
   * @return 二维码会话通过对话
   */
  Map<String, Object> findQrSessionByChat(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("chatSessionId") String chatSessionId,
      @Param("now") Instant now);

  /**
   * 删除二维码会话。
   *
   * @param qrSessionId 二维码会话标识
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 二维码会话
   */
  int deleteQrSession(
      @Param("qrSessionId") String qrSessionId,
      @Param("tenantId") String tenantId,
      @Param("userId") String userId);

  /**
   * 删除指定属主的全部二维码会话。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 删除记录数
   */
  int deleteQrSessionsForOwner(@Param("tenantId") String tenantId, @Param("userId") String userId);

  /**
   * 删除过期二维码会话列表。
   *
   * @param now 当前时间
   * @return Expired 二维码 Sessions
   */
  int deleteExpiredQrSessions(@Param("now") Instant now);
}
