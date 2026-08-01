package com.jobbuddy.backend.modules.resume.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证岗位匹配补充证据只保留公开作品与研发能力事实。
 */
class ResumeMatchEvidenceExtractorTest {

  /**
   * 验证公开链接、AI 工具、技术写作和开源项目证据不会在结构化解析之外丢失。
   */
  @Test
  void extractsPublicWorkAndAiDevelopmentEvidence() {
    String text =
        String.join(
            "\n",
            "联系方式：13800000000 / candidate@example.com",
            "技术博客：https://example.dev / https://notes.example.dev",
            "项目仓库：https://github.com/example",
            "AI 原生研发：熟练使用 Claude Code、Codex 等 AI 辅助编程工具。",
            "智能求职平台 - 个人开源项目 - AI 原生独立开发者",
            "普通工作经历：负责业务接口研发。",
            "开源仓库：https://github.com/example/job-buddy");

    List<String> evidence = ResumeMatchEvidenceExtractor.extractFromText(text);

    assertEquals(5, evidence.size());
    assertTrue(
        evidence.stream().anyMatch(item -> item.contains("Claude Code") && item.contains("Codex")));
    assertTrue(evidence.stream().anyMatch(item -> item.contains("技术博客")));
    assertTrue(evidence.stream().anyMatch(item -> item.contains("个人开源项目")));
    assertTrue(evidence.stream().anyMatch(item -> item.contains("github.com/example/job-buddy")));
    assertFalse(evidence.stream().anyMatch(item -> item.contains("13800000000")));
  }
}
