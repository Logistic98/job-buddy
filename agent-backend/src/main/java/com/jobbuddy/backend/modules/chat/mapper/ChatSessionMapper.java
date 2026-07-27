package com.jobbuddy.backend.modules.chat.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 映射对话会话数据记录。
 */
public interface ChatSessionMapper {
  /**
   * 按标识查询记录。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param sessionId 会话标识
   * @return 按标识查询到的记录
   */
  Map<String, Object> findById(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("sessionId") String sessionId);

  /**
   * 新增或更新状态。
   *
   * @param state 状态
   * @return 状态
   */
  int upsertState(@Param("state") Map<String, Object> state);

  /**
   * 追加会话消息。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param sessionId 会话标识
   * @param role 角色
   * @param content 内容
   * @param metadataJson 元数据 JSON
   * @param createdAt 创建时间
   * @return 受影响的记录数
   */
  int appendMessage(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("sessionId") String sessionId,
      @Param("role") String role,
      @Param("content") String content,
      @Param("metadataJson") String metadataJson,
      @Param("createdAt") Instant createdAt);

  /**
   * 按幂等约束追加用户消息。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param sessionId 会话标识
   * @param turnId 轮次标识
   * @param content 内容
   * @param metadataJson 元数据 JSON
   * @param createdAt 创建时间
   * @return 新增记录时为 1，幂等重放时为 0
   */
  int appendUserMessageOnce(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("sessionId") String sessionId,
      @Param("turnId") String turnId,
      @Param("content") String content,
      @Param("metadataJson") String metadataJson,
      @Param("createdAt") Instant createdAt);

  /**
   * 按轮次标识查询用户消息内容。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param sessionId 会话标识
   * @param turnId 轮次标识
   * @return 指定轮次的用户消息内容
   */
  Map<String, Object> findUserMessageByTurnId(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("sessionId") String sessionId,
      @Param("turnId") String turnId);

  /**
   * 查找最近助手岗位消息。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param sessionId 会话标识
   * @return 最近一条助手岗位消息
   */
  Map<String, Object> findLatestAssistantJobMessage(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("sessionId") String sessionId);

  /**
   * 更新消息元数据。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param id 标识
   * @param metadataJson 元数据 JSON
   * @return 消息 Metadata
   */
  int updateMessageMetadata(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("id") Long id,
      @Param("metadataJson") String metadataJson);

  /**
   * 查询会话列表。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 会话列表
   */
  List<Map<String, Object>> listSessions(
      @Param("tenantId") String tenantId, @Param("userId") String userId);

  /**
   * 查询消息列表。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param sessionId 会话标识
   * @return 消息列表
   */
  List<Map<String, Object>> listMessages(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("sessionId") String sessionId);

  /**
   * 删除消息列表。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param sessionId 会话标识
   * @return 删除的消息数
   */
  int deleteMessages(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("sessionId") String sessionId);

  /**
   * 删除状态。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param sessionId 会话标识
   * @return 状态
   */
  int deleteState(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("sessionId") String sessionId);
}
