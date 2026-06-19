package com.jobbuddy.backend.modules.job.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/**
 * MyBatis mapper for a user's favorited job snapshots.
 */
public interface JobFavoriteMapper {

    List<Map<String, Object>> listFavorites(@Param("userId") String userId);

    Map<String, Object> findFavorite(
            @Param("userId") String userId,
            @Param("jobKey") String jobKey);

    int updateAnalysis(
            @Param("userId") String userId,
            @Param("jobKey") String jobKey,
            @Param("jobJson") String jobJson);

    int upsertFavorite(
            @Param("favoriteId") String favoriteId,
            @Param("userId") String userId,
            @Param("jobKey") String jobKey,
            @Param("jobJson") String jobJson);

    int removeFavorite(
            @Param("userId") String userId,
            @Param("jobKey") String jobKey);
}
