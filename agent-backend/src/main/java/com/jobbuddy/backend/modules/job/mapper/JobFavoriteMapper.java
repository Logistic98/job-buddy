package com.jobbuddy.backend.modules.job.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 用户收藏岗位快照的 MyBatis Mapper。
 */
public interface JobFavoriteMapper {

  /**
   * 查询收藏岗位列表。
   *
   * @param userId 用户标识
   * @return 收藏岗位列表
   */
  List<Map<String, Object>> listFavorites(@Param("userId") String userId);

  /**
   * 查找收藏岗位。
   *
   * @param userId 用户标识
   * @param jobKey 岗位键
   * @return 收藏岗位
   */
  Map<String, Object> findFavorite(@Param("userId") String userId, @Param("jobKey") String jobKey);

  /**
   * 更新分析。
   *
   * @param userId 用户标识
   * @param jobKey 岗位键
   * @param analysisJson 分析结果 JSON
   * @param analyzedAt 分析时间
   * @return 受影响的记录数
   */
  int updateAnalysis(
      @Param("userId") String userId,
      @Param("jobKey") String jobKey,
      @Param("analysisJson") String analysisJson,
      @Param("analyzedAt") Instant analyzedAt);

  /**
   * 新增或更新收藏岗位。
   *
   * @param userId 用户标识
   * @param jobKey 岗位键
   * @param jobJson 岗位 JSON
   * @return 收藏岗位
   */
  int upsertFavorite(
      @Param("userId") String userId,
      @Param("jobKey") String jobKey,
      @Param("jobJson") String jobJson);

  /**
   * 删除收藏岗位。
   *
   * @param userId 用户标识
   * @param jobKey 岗位键
   * @return 收藏岗位
   */
  int removeFavorite(@Param("userId") String userId, @Param("jobKey") String jobKey);
}
