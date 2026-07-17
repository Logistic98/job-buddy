package com.jobbuddy.backend.modules.resume.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** MyBatis mapper for resume writer version snapshots. */
@Mapper
public interface ResumeWriterVersionMapper {

  List<Map<String, Object>> listByOwner(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("limit") int limit);

  Map<String, Object> findByIdAndOwner(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("versionId") String versionId);

  Long maxVersionNo(@Param("tenantId") String tenantId, @Param("userId") String userId);

  int insertVersion(@Param("version") Map<String, Object> version);

  int deleteByIdAndOwner(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("versionId") String versionId);

  int deleteBeyondLimit(
      @Param("tenantId") String tenantId, @Param("userId") String userId, @Param("keep") int keep);
}
