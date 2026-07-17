package com.jobbuddy.backend.modules.resume.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ResumeAssetMapper {
  Map<String, Object> findByAssetIdAndUser(Map<String, Object> query);

  int insertAsset(Map<String, Object> asset);
}
