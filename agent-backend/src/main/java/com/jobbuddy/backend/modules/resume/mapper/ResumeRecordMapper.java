package com.jobbuddy.backend.modules.resume.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 上传或同步简历记录的 MyBatis Mapper。
 */
public interface ResumeRecordMapper {

  /**
   * 按标识查询记录。
   *
   * @param resumeId 简历标识
   * @return 按标识查询到的记录
   */
  Map<String, Object> findById(@Param("resumeId") String resumeId);

  /**
   * 查询用户最近上传的简历。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param limit 数量上限
   * @return 最近通过用户标识
   */
  List<Map<String, Object>> findLatestByUserId(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("limit") int limit);

  /**
   * 查询用户最近简历的摘要。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param limit 数量上限
   * @return 最近摘要通过用户标识
   */
  List<Map<String, Object>> findLatestSummariesByUserId(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("limit") int limit);

  /**
   * 统计按标识。
   *
   * @param resumeId 简历标识
   * @return 统计数量
   */
  int countById(@Param("resumeId") String resumeId);

  /**
   * 查询用户所属租户标识。
   *
   * @param userId 用户标识
   * @return 用户所属租户标识
   */
  String findTenantIdByUserId(@Param("userId") String userId);

  /**
   * 新增记录。
   *
   * @param record 记录
   * @return 记录
   */
  int insertRecord(@Param("record") Map<String, Object> record);

  /**
   * 更新记录。
   *
   * @param record 记录
   * @return 记录
   */
  int updateRecord(@Param("record") Map<String, Object> record);

  /**
   * 按标识删除记录。
   *
   * @param resumeId 简历标识
   * @return 按标识
   */
  int deleteById(@Param("resumeId") String resumeId);
}
