package com.jobbuddy.backend.modules.system.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 映射用户工作区数据记录。
 */
@Mapper
public interface UserWorkspaceMapper {
  /**
   * 查找状态 JSON。
   *
   * @param userId 用户标识
   * @param stateKey 状态键
   * @return 状态 JSON
   */
  String findStateJson(@Param("userId") String userId, @Param("stateKey") String stateKey);

  /**
   * 新增或更新状态。
   *
   * @param userId 用户标识
   * @param stateKey 状态键
   * @param stateJson 状态 JSON
   * @return 状态
   */
  int upsertState(
      @Param("userId") String userId,
      @Param("stateKey") String stateKey,
      @Param("stateJson") String stateJson);

  /**
   * 删除状态。
   *
   * @param userId 用户标识
   * @param stateKey 状态键
   * @return 状态
   */
  int deleteState(@Param("userId") String userId, @Param("stateKey") String stateKey);
}
