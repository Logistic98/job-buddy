package com.jobbuddy.backend.modules.system.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jobbuddy.backend.modules.system.mapper.UserWorkspaceMapper;
import com.jobbuddy.backend.modules.system.service.UserWorkspaceService;
import org.springframework.stereotype.Service;

/**
 * 使用校验后的命名空间键持久化用户工作区快照。
 *
 * <p>返回 JSON 使用副本或重新解析，防止调用方修改共享实例。
 */
@Service
public class UserWorkspaceServiceImpl implements UserWorkspaceService {
  private final UserWorkspaceMapper mapper;
  private final ObjectMapper objectMapper;

  /**
   * 创建用户工作区服务实例。
   *
   * @param mapper 数据映射
   * @param objectMapper JSON 对象映射器
   */
  public UserWorkspaceServiceImpl(UserWorkspaceMapper mapper, ObjectMapper objectMapper) {
    this.mapper = mapper;
    this.objectMapper = objectMapper;
  }

  /**
   * 读取用户工作区状态。
   *
   * @param userId 用户标识
   * @param stateKey 状态键
   * @return 查询结果
   */
  @Override
  public JsonNode get(String userId, String stateKey) {
    validate(userId, stateKey);
    String json = mapper.findStateJson(userId, stateKey);
    if (json == null || json.trim().isEmpty()) return JsonNodeFactory.instance.objectNode();
    try {
      return objectMapper.readTree(json);
    } catch (Exception e) {
      throw new RuntimeException("读取用户工作区状态失败: " + e.getMessage(), e);
    }
  }

  /**
   * 保存用户工作区。
   *
   * @param userId 用户标识
   * @param stateKey 状态键
   * @param state 状态
   * @return 保存后的用户工作区
   */
  @Override
  public JsonNode save(String userId, String stateKey, JsonNode state) {
    validate(userId, stateKey);
    JsonNode safe =
        state == null || state.isNull() ? JsonNodeFactory.instance.objectNode() : state.deepCopy();
    try {
      mapper.upsertState(userId, stateKey, objectMapper.writeValueAsString(safe));
      return safe;
    } catch (Exception e) {
      throw new RuntimeException("保存用户工作区状态失败: " + e.getMessage(), e);
    }
  }

  /**
   * 删除用户工作区。
   *
   * @param userId 用户标识
   * @param stateKey 状态键
   */
  @Override
  public void delete(String userId, String stateKey) {
    validate(userId, stateKey);
    mapper.deleteState(userId, stateKey);
  }

  /**
   * 校验用户工作区。
   *
   * @param userId 用户标识
   * @param stateKey 状态键
   */
  private void validate(String userId, String stateKey) {
    if (userId == null || userId.trim().isEmpty()) throw new IllegalArgumentException("用户不能为空");
    if (stateKey == null || !stateKey.matches("[a-z0-9][a-z0-9._-]{0,127}")) {
      throw new IllegalArgumentException("非法工作区状态键");
    }
  }
}
