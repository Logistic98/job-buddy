package com.jobbuddy.backend.modules.resume.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 简历撰写器版本快照的 MyBatis Mapper。
 */
@Mapper
public interface ResumeWriterVersionMapper {

  /**
   * 按属主查询版本列表。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param limit 数量上限
   * @return 通过属主
   */
  List<Map<String, Object>> listByOwner(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("limit") int limit);

  /**
   * 按标识和属主查询版本。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param versionId 版本标识
   * @return 按标识查询到的记录并属主
   */
  Map<String, Object> findByIdAndOwner(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("versionId") String versionId);

  /**
   * 获取最大版本号。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return max 版本 No
   */
  Long maxVersionNo(@Param("tenantId") String tenantId, @Param("userId") String userId);

  /**
   * 新增版本。
   *
   * @param version 版本
   * @return 版本
   */
  int insertVersion(@Param("version") Map<String, Object> version);

  /**
   * 按标识和属主删除版本。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param versionId 版本标识
   * @return 按标识和属主删除的记录数
   */
  int deleteByIdAndOwner(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("versionId") String versionId);

  /**
   * 删除超出上限。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param keep 需保留数量
   * @return 删除的版本数
   */
  int deleteBeyondLimit(
      @Param("tenantId") String tenantId, @Param("userId") String userId, @Param("keep") int keep);
}
