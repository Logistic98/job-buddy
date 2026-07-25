package com.jobbuddy.backend.modules.system.service;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 定义用户工作区服务契约。
 */
public interface UserWorkspaceService {
  /**
   * 读取用户工作区状态。
   *
   * @param userId 用户标识
   * @param stateKey 状态键
   * @return 查询结果
   */
  JsonNode get(String userId, String stateKey);

  /**
   * 保存用户工作区。
   *
   * @param userId 用户标识
   * @param stateKey 状态键
   * @param state 状态
   * @return 保存后的用户工作区
   */
  JsonNode save(String userId, String stateKey, JsonNode state);

  /**
   * 删除用户工作区。
   *
   * @param userId 用户标识
   * @param stateKey 状态键
   */
  void delete(String userId, String stateKey);
}
