package com.jobbuddy.backend.modules.system.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserWorkspaceMapper {
  String findStateJson(@Param("userId") String userId, @Param("stateKey") String stateKey);

  int upsertState(
      @Param("userId") String userId,
      @Param("stateKey") String stateKey,
      @Param("stateJson") String stateJson);

  int deleteState(@Param("userId") String userId, @Param("stateKey") String stateKey);
}
