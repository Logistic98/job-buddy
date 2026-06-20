package com.jobbuddy.backend.modules.auth.mapper;

import java.util.Map;

import org.apache.ibatis.annotations.Param;

/**
 * MyBatis mapper for the persisted third-party authentication state.
 */
public interface AuthStateMapper {

    Map<String, Object> findByProvider(@Param("provider") String provider);

    int countByProvider(@Param("provider") String provider);

    int insertState(@Param("state") Map<String, Object> state);

    int updateState(@Param("state") Map<String, Object> state);
}
