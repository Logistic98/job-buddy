package com.jobbuddy.backend.modules.system.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** MyBatis mapper for system-level settings that are stored in relational tables. */
@Mapper
public interface SystemSettingsMapper {

  List<Map<String, Object>> listBlacklistItems();

  String findSettingJson(@Param("scopeId") String scopeId, @Param("settingKey") String settingKey);

  int upsertSetting(
      @Param("scopeId") String scopeId,
      @Param("settingKey") String settingKey,
      @Param("settingJson") String settingJson);

  int deleteSetting(@Param("scopeId") String scopeId, @Param("settingKey") String settingKey);
}
