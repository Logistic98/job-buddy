package com.jobbuddy.backend.modules.prompt.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProfileDocumentMapper {
  List<Map<String, Object>> listContextDocuments(@Param("userId") String userId);
}
