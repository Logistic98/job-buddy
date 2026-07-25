package com.jobbuddy.backend.modules.resume.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

/**
 * 映射简历资源数据记录。
 */
@Mapper
public interface ResumeAssetMapper {
  /**
   * 按资源标识和用户查询简历附件。
   *
   * @param query 查询条件
   * @return 简历附件
   */
  Map<String, Object> findByAssetIdAndUser(Map<String, Object> query);

  /**
   * 新增附件。
   *
   * @param asset 资源
   * @return 附件
   */
  int insertAsset(Map<String, Object> asset);
}
