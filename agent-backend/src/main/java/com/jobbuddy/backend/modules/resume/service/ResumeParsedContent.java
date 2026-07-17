package com.jobbuddy.backend.modules.resume.service;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 判断简历 parsed 载荷是否包含真实解析内容。 文件夹整理等操作会把 folder/resumeFolder 元数据写入 parsed_json，使记录看似"已解析"。
 * 聊天与匹配链路必须以内容字段而非"非空"作为已解析依据，否则会跳过真正的简历解析， 导致模型只拿到文件名等元数据。
 */
public final class ResumeParsedContent {
  private static final Set<String> METADATA_ONLY_KEYS =
      new HashSet<String>(
          Arrays.asList("folder", "resumeFolder", "version", "labels", "manageTags", "updatedAt"));

  private ResumeParsedContent() {}

  public static boolean hasContent(Map<String, Object> parsed) {
    if (parsed == null || parsed.isEmpty()) return false;
    for (Map.Entry<String, Object> entry : parsed.entrySet()) {
      if (METADATA_ONLY_KEYS.contains(entry.getKey())) continue;
      Object value = entry.getValue();
      if (value == null) continue;
      if (value instanceof CharSequence && value.toString().trim().isEmpty()) continue;
      if (value instanceof Collection && ((Collection<?>) value).isEmpty()) continue;
      if (value instanceof Map && ((Map<?, ?>) value).isEmpty()) continue;
      return true;
    }
    return false;
  }
}
