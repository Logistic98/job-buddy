package com.jobbuddy.backend.modules.resume.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResumeParsedContentTest {

  @Test
  void nullOrEmptyParsedHasNoContent() {
    assertFalse(ResumeParsedContent.hasContent(null));
    assertFalse(ResumeParsedContent.hasContent(Collections.<String, Object>emptyMap()));
  }

  @Test
  void folderMetadataOnlyParsedHasNoContent() {
    Map<String, Object> parsed = new LinkedHashMap<String, Object>();
    parsed.put("folder", "大模型");
    parsed.put("resumeFolder", "大模型");
    parsed.put("version", 3);
    parsed.put("labels", Arrays.asList("Java方向"));
    parsed.put("manageTags", Arrays.asList("杭州", "20-30k"));
    parsed.put("updatedAt", "2026-07-22T16:20:00+08:00");
    parsed.put("analysis", Collections.singletonMap("summary", "简历分析报告"));
    assertFalse(ResumeParsedContent.hasContent(parsed));
  }

  @Test
  void blankValuesDoNotCountAsContent() {
    Map<String, Object> parsed = new LinkedHashMap<String, Object>();
    parsed.put("name", "  ");
    parsed.put("skills", Collections.emptyList());
    parsed.put("contact", Collections.emptyMap());
    assertFalse(ResumeParsedContent.hasContent(parsed));
  }

  @Test
  void realResumeFieldsCountAsContent() {
    Map<String, Object> parsed = new LinkedHashMap<String, Object>();
    parsed.put("folder", "大模型");
    parsed.put("name", "示例候选人");
    parsed.put("skills", Arrays.asList("Java", "Python"));
    assertTrue(ResumeParsedContent.hasContent(parsed));
  }
}
