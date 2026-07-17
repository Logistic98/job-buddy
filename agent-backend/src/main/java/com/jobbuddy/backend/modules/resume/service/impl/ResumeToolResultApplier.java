package com.jobbuddy.backend.modules.resume.service.impl;

import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Runtime 工具结果落盘：严格校验 resume_parse / resume_analyze 结果并写回简历记录。 */
class ResumeToolResultApplier {

  Map<String, Object> analysisArgs(Path tempFile, ResumeRecord record) {
    return analysisArgs(tempFile, record, null);
  }

  Map<String, Object> analysisArgs(
      Path tempFile, ResumeRecord record, java.util.List<String> sections) {
    Map<String, Object> args = new LinkedHashMap<String, Object>();
    args.put("file_path", tempFile.toString());
    args.put("parsed", compactParsed(record.getParsed()));
    if (sections != null && !sections.isEmpty()) args.put("sections", sections);
    return args;
  }

  private Map<String, Object> compactParsed(Map<String, Object> parsed) {
    if (parsed == null || parsed.isEmpty()) return Collections.emptyMap();
    Map<String, Object> compact = new LinkedHashMap<String, Object>();
    for (Map.Entry<String, Object> entry : parsed.entrySet()) {
      String key = entry.getKey();
      if ("raw_text".equals(key) || "markdown".equals(key) || "analysis".equals(key)) continue;
      if ("source".equals(key) && entry.getValue() instanceof Map) {
        Map<String, Object> source =
            new LinkedHashMap<String, Object>((Map<String, Object>) entry.getValue());
        source.remove("raw");
        compact.put(key, source);
      } else {
        compact.put(key, entry.getValue());
      }
    }
    return compact;
  }

  void mergeAnalysisResult(ResumeRecord record, Map<String, Object> result) {
    if (!Boolean.TRUE.equals(result.get("success"))) {
      throw new RuntimeException(stringOf(result.get("error")));
    }
    Object output = result.get("output");
    Object value = output instanceof Map ? ((Map) output).get("analysis") : null;
    if (!(value instanceof Map)) throw new RuntimeException("Runtime 简历分析未返回有效分组结果");
    Map<String, Object> parsed =
        record.getParsed() == null
            ? new LinkedHashMap<String, Object>()
            : new LinkedHashMap<String, Object>(record.getParsed());
    Object previous = parsed.get("analysis");
    Map<String, Object> merged =
        previous instanceof Map
            ? new LinkedHashMap<String, Object>((Map<String, Object>) previous)
            : new LinkedHashMap<String, Object>();
    merged.putAll((Map<String, Object>) value);
    parsed.put("analysis", merged);
    record.setParsed(parsed);
  }

  void applyAnalysisResult(ResumeRecord record, Map<String, Object> result) {
    if (!Boolean.TRUE.equals(result.get("success"))) {
      throw new RuntimeException(stringOf(result.get("error")));
    }
    Object output = result.get("output");
    Object analysis = output instanceof Map ? ((Map) output).get("analysis") : null;
    if (!(analysis instanceof Map)) {
      throw new RuntimeException("Runtime 简历分析未返回有效结果");
    }
    Map<String, Object> parsed =
        record.getParsed() == null
            ? new LinkedHashMap<String, Object>()
            : new LinkedHashMap<String, Object>(record.getParsed());
    parsed.put("analysis", analysis);
    record.setParsed(parsed);
  }

  void applyParseResult(ResumeRecord record, Map<String, Object> result) {
    boolean success = Boolean.TRUE.equals(result.get("success"));
    if (!success) {
      String error = stringOf(result.get("error"));
      record.setParseStatus("fail");
      record.setParseError(error);
      throw new RuntimeException(error);
    }
    Object output = result.get("output");
    Object parsed = output instanceof Map ? ((Map) output).get("resume") : null;
    if (!(parsed instanceof Map)) {
      throw new RuntimeException("Runtime 简历解析未返回有效结果");
    }
    Map<String, Object> next = new LinkedHashMap<String, Object>((Map<String, Object>) parsed);
    // 解析结果覆盖 parsed 时保留文件夹归属元数据，避免重新解析后简历从分组目录中消失。
    Map<String, Object> previous = record.getParsed();
    if (previous != null) {
      for (String key : new String[] {"folder", "resumeFolder"}) {
        if (previous.containsKey(key) && !next.containsKey(key)) next.put(key, previous.get(key));
      }
    }
    record.setParsed(next);
    record.setParseStatus("success");
    record.setParseError(null);
  }

  private String stringOf(Object value) {
    return value == null ? "" : String.valueOf(value);
  }
}
