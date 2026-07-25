package com.jobbuddy.backend.modules.journey.mapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 求职目标与投递旅程记录的 MyBatis Mapper。
 */
@Mapper
public interface JobJourneyMapper {

  /**
   * 查找目标。
   *
   * @param userId 用户标识
   * @return 目标
   */
  Map<String, Object> findTarget(@Param("userId") String userId);

  /**
   * 统计目标。
   *
   * @param targetId 目标标识
   * @return 统计数量
   */
  int countTarget(@Param("targetId") Object targetId);

  /**
   * 新增目标。
   *
   * @param target 求职目标数据
   * @return 受影响的记录数
   */
  int insertTarget(Map<String, Object> target);

  /**
   * 更新目标。
   *
   * @param target 求职目标数据
   * @return 受影响的记录数
   */
  int updateTarget(Map<String, Object> target);

  /**
   * 查询记录列表。
   *
   * @param userId 用户标识
   * @param keyword 关键词
   * @param status 状态
   * @param result 结果
   * @return 记录列表
   */
  List<Map<String, Object>> listRecords(
      @Param("userId") String userId,
      @Param("keyword") String keyword,
      @Param("status") String status,
      @Param("result") String result);

  /**
   * 查找记录。
   *
   * @param recordId 记录标识
   * @return 记录
   */
  Map<String, Object> findRecord(@Param("recordId") String recordId);

  /**
   * 统计记录。
   *
   * @param recordId 记录标识
   * @return 统计数量
   */
  int countRecord(@Param("recordId") Object recordId);

  /**
   * 新增记录。
   *
   * @param record 记录
   * @return 记录
   */
  int insertRecord(Map<String, Object> record);

  /**
   * 更新记录。
   *
   * @param record 记录
   * @return 记录
   */
  int updateRecord(Map<String, Object> record);

  /**
   * 软删除指定求职记录。
   *
   * @param recordId 记录标识
   * @param updatedAt 更新时间
   * @return 记录
   */
  int deleteRecord(@Param("recordId") String recordId, @Param("updatedAt") Timestamp updatedAt);
}
