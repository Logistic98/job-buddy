package com.jobbuddy.backend.modules.chat.service;

import com.jobbuddy.backend.modules.chat.dto.response.ChatMessageResponse;
import com.jobbuddy.backend.modules.chat.dto.response.ChatSessionResponse;
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import java.util.List;
import java.util.Map;

/**
 * 管理聊天会话持久化、乐观状态缓存与消息幂等。
 *
 * <p>会话必须绑定租户和用户后才可接受变更。
 */
public interface ChatSessionStore {
  /**
   * 绑定属主。
   *
   * @param sessionId 会话标识
   * @param tenantId 租户标识
   * @param userId 用户标识
   */
  void bindOwner(String sessionId, String tenantId, String userId);

  /**
   * 获取或创建会话。
   *
   * @param sessionId 会话标识
   * @return 或创建会话
   */
  ChatSessionState getOrCreate(String sessionId);

  /**
   * 按标识读取数据。
   *
   * @param sessionId 会话标识
   * @return 查询结果
   */
  ChatSessionState get(String sessionId);

  /**
   * 保存会话数据。
   *
   * @param state 状态
   */
  void save(ChatSessionState state);

  /**
   * 追加消息。
   *
   * @param sessionId 会话标识
   * @param role 角色
   * @param content 内容
   */
  void appendMessage(String sessionId, String role, String content);

  /**
   * 追加消息。
   *
   * @param sessionId 会话标识
   * @param role 角色
   * @param content 内容
   * @param metadata 扩展元数据
   */
  void appendMessage(String sessionId, String role, String content, Map<String, Object> metadata);

  /**
   * 仅在客户端生成的稳定轮次标识尚未落库时追加用户消息。
   *
   * @param sessionId 会话标识
   * @param turnId 对话轮次标识
   * @param content 内容
   * @return 新增记录时为 {@code true}，幂等重放时为 {@code false}
   */
  boolean appendUserMessageOnce(String sessionId, String turnId, String content);

  /**
   * 仅追加一次携带结构化元数据的用户消息。
   *
   * @param sessionId 会话标识
   * @param turnId 对话轮次标识
   * @param content 内容
   * @param metadata 消息元数据
   * @return 新增记录时为 {@code true}，幂等重放时为 {@code false}
   */
  boolean appendUserMessageOnce(
      String sessionId, String turnId, String content, Map<String, Object> metadata);

  /**
   * 替换最新助手岗位消息。
   *
   * @param sessionId 会话标识
   * @param jobs 岗位列表
   * @param toolEvents 工具事件列表
   * @return 更新后的消息列表
   */
  boolean replaceLatestAssistantJobMessage(
      String sessionId, List<Map<String, Object>> jobs, List<Map<String, Object>> toolEvents);

  /**
   * 新增或更新工具事件。
   *
   * @param sessionId 会话标识
   * @param event 事件名称
   */
  void upsertToolEvent(String sessionId, Map<String, Object> event);

  /**
   * 合并结构化简历匹配快照，同时保留其他会话元数据。
   *
   * @param sessionId 会话标识
   * @param match 匹配结果
   */
  void updateResumeMatch(String sessionId, Map<String, Object> match);

  /**
   * 查询会话列表。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 会话列表
   */
  List<ChatSessionResponse> listSessions(String tenantId, String userId);

  /**
   * 查询消息列表。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param sessionId 会话标识
   * @return 消息列表
   */
  List<ChatMessageResponse> listMessages(String tenantId, String userId, String sessionId);

  /**
   * 清理会话数据。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param sessionId 会话标识
   */
  void clear(String tenantId, String userId, String sessionId);
}
