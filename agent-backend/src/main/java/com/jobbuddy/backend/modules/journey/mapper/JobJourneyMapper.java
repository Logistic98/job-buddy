package com.jobbuddy.backend.modules.journey.mapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MyBatis mapper for job-search targets and application journey records.
 */
@Mapper
public interface JobJourneyMapper {

    Map<String, Object> findTarget(@Param("userId") String userId);

    int countTarget(@Param("targetId") Object targetId);

    int insertTarget(Map<String, Object> target);

    int updateTarget(Map<String, Object> target);

    List<Map<String, Object>> listRecords(
            @Param("userId") String userId,
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("result") String result);

    Map<String, Object> findRecord(@Param("recordId") String recordId);

    int countRecord(@Param("recordId") Object recordId);

    int insertRecord(Map<String, Object> record);

    int updateRecord(Map<String, Object> record);

    int deleteRecord(
            @Param("recordId") String recordId,
            @Param("updatedAt") Timestamp updatedAt);
}
