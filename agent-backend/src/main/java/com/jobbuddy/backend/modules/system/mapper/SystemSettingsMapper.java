package com.jobbuddy.backend.modules.system.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;

/**
 * MyBatis mapper for system-level settings that are stored in relational tables.
 */
@Mapper
public interface SystemSettingsMapper {

    List<Map<String, Object>> listBlacklistItems();
}
