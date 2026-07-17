package com.jobbuddy.backend.modules.system.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jobbuddy.backend.modules.system.mapper.UserWorkspaceMapper;
import org.springframework.stereotype.Service;

@Service
public class UserWorkspaceService {
  private final UserWorkspaceMapper mapper;
  private final ObjectMapper objectMapper;

  public UserWorkspaceService(UserWorkspaceMapper mapper, ObjectMapper objectMapper) {
    this.mapper = mapper;
    this.objectMapper = objectMapper;
  }

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

  public void delete(String userId, String stateKey) {
    validate(userId, stateKey);
    mapper.deleteState(userId, stateKey);
  }

  private void validate(String userId, String stateKey) {
    if (userId == null || userId.trim().isEmpty()) throw new IllegalArgumentException("用户不能为空");
    if (stateKey == null || !stateKey.matches("[a-z0-9][a-z0-9._-]{0,127}")) {
      throw new IllegalArgumentException("非法工作区状态键");
    }
  }
}
