package com.jobbuddy.backend.modules.resume.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** MyBatis mapper for uploaded or synchronized resume records. */
public interface ResumeRecordMapper {

  Map<String, Object> findById(@Param("resumeId") String resumeId);

  List<Map<String, Object>> findLatestByUserId(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("limit") int limit);

  List<Map<String, Object>> findLatestSummariesByUserId(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("limit") int limit);

  int countById(@Param("resumeId") String resumeId);

  String findTenantIdByUserId(@Param("userId") String userId);

  int insertRecord(@Param("record") Map<String, Object> record);

  int updateRecord(@Param("record") Map<String, Object> record);

  int deleteById(@Param("resumeId") String resumeId);
}
