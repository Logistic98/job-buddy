package com.jobbuddy.backend.modules.resume.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 验证 ResumeParsedContent 的核心行为、异常路径与边界条件。
 */
class ResumeParsedContentTest {

  /**
   * 验证 ResumeParsedContent 的核心业务契约。
   */
  @Test
  void nullOrEmptyParsedHasNoContent() {
    assertFalse(ResumeParsedContent.hasContent(null));
    assertFalse(ResumeParsedContent.hasContent(Collections.<String, Object>emptyMap()));
  }

  /**
   * 验证 ResumeParsedContent 的数据转换与协议契约。
   */
  @Test
  void folderMetadataOnlyParsedHasNoContent() {
    Map<String, Object> parsed = new LinkedHashMap<String, Object>();
    parsed.put("folder", "大模型");
    parsed.put("resumeFolder", "大模型");
    parsed.put("version", 3);
    parsed.put("labels", Arrays.asList("Java方向"));
    parsed.put("manageTags", Arrays.asList("杭州", "20-30k"));
    parsed.put("updatedAt", "2026-07-22T16:20:00+08:00");
    assertFalse(ResumeParsedContent.hasContent(parsed));
  }

  /**
   * 验证 ResumeParsedContent 的输入校验与拒绝边界。
   */
  @Test
  void blankValuesDoNotCountAsContent() {
    Map<String, Object> parsed = new LinkedHashMap<String, Object>();
    parsed.put("name", "  ");
    parsed.put("skills", Collections.emptyList());
    parsed.put("contact", Collections.emptyMap());
    assertFalse(ResumeParsedContent.hasContent(parsed));
  }

  /**
   * 验证 ResumeParsedContent 中简历的数量、长度与分页边界。
   */
  @Test
  void realResumeFieldsCountAsContent() {
    Map<String, Object> parsed = new LinkedHashMap<String, Object>();
    parsed.put("folder", "大模型");
    parsed.put("name", "示例候选人");
    parsed.put("skills", Arrays.asList("Java", "Python"));
    assertTrue(ResumeParsedContent.hasContent(parsed));
  }
}
