package com.jobbuddy.backend.modules.system.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 关系表中系统级设置的 MyBatis Mapper。
 */
@Mapper
public interface SystemSettingsMapper {

  /**
   * 查询黑名单数据项列表。
   *
   * @return 黑名单数据项列表
   */
  List<Map<String, Object>> listBlacklistItems();

  /**
   * 查找设置 JSON。
   *
   * @param scopeId 作用域标识
   * @param settingKey 设置键
   * @return 设置 JSON
   */
  String findSettingJson(@Param("scopeId") String scopeId, @Param("settingKey") String settingKey);

  /**
   * 新增或更新设置。
   *
   * @param scopeId 作用域标识
   * @param settingKey 设置键
   * @param settingJson 设置 JSON
   * @return 设置
   */
  int upsertSetting(
      @Param("scopeId") String scopeId,
      @Param("settingKey") String settingKey,
      @Param("settingJson") String settingJson);

  /**
   * 删除设置。
   *
   * @param scopeId 作用域标识
   * @param settingKey 设置键
   * @return 设置
   */
  int deleteSetting(@Param("scopeId") String scopeId, @Param("settingKey") String settingKey);
}
