package com.jobbuddy.backend.modules.resume.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.jobbuddy.backend.common.util.JsonCodec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BossProfileNormalizerTest {
  private final BossProfileNormalizer normalizer = new BossProfileNormalizer(new JsonCodec());

  @Test
  void groupedProfileHasPriorityAndPreservesExperienceAliases() {
    Map<String, Object> work = Map.of("company", "example");
    Map<String, Object> education = Map.of("school", "example");
    Map<String, Object> project = Map.of("title", "example");
    Map<String, Object> expectations = Map.of("expected_titles", List.of("Java"));
    Map<String, Object> raw =
        Map.of(
            "basicInfo",
            Map.of(
                "name",
                "primary",
                "currentTitle",
                "developer",
                "workYears",
                6,
                "skills",
                List.of("Java")),
            "userInfo",
            Map.of("name", "fallback"),
            "jobExpectations",
            expectations,
            "jobStatus",
            Map.of("status", "available"),
            "workExperiences",
            work,
            "educationExperiences",
            education,
            "projectExperiences",
            project,
            "jobIntentions",
            Map.of("city", "example"),
            "personalAdvantage",
            Map.of("text", "experience"));

    Map<String, Object> parsed = normalizer.normalizeBossProfile(raw);

    assertEquals("primary", parsed.get("name"));
    assertEquals(6, parsed.get("years_experience"));
    assertEquals(List.of("Java"), parsed.get("expected_titles"));
    assertEquals(work, parsed.get("experiences"));
    assertEquals(education, parsed.get("education"));
    assertEquals(project, parsed.get("projects"));
    assertEquals(expectations, parsed.get("expectations"));
    assertEquals("developer", parsed.get("current_title"));
    assertEquals(raw, ((Map<?, ?>) parsed.get("source")).get("raw"));
  }

  @Test
  void legacyNestedExperienceIsRecoveredFromListAndEmptyValuesAreIgnored() {
    Map<String, Object> work = Map.of("company", "sample");
    Map<String, Object> parsed =
        normalizer.normalizeBossProfile(
            Map.of(
                "basicInfo",
                Map.of("name", " "),
                "name",
                "fallback",
                "nested",
                List.of("ignored", Map.of("workExpList", List.of(work)))));
    assertEquals("fallback", parsed.get("name"));
    assertEquals(List.of(work), parsed.get("work_experiences"));
  }

  @Test
  void recursiveLookupStopsAtDepthLimit() {
    Map<String, Object> nested = Map.of("workExpList", List.of("too deep"));
    for (int i = 0; i < 8; i++) nested = Map.of("child", nested);
    assertNull(normalizer.normalizeBossProfile(nested).get("work_experiences"));
  }

  @Test
  void explicitSummaryWinsOverGeneratedText() {
    assertEquals(
        "explicit",
        normalizer
            .normalizeBossProfile(Map.of("summary", "explicit", "name", "candidate"))
            .get("summary"));
  }

  @Test
  void manualProfileHasStableEmptyFieldsAndSource() {
    Map<String, Object> parsed = normalizer.emptyJobProfile();
    assertEquals("", parsed.get("name"));
    assertEquals(List.of(), parsed.get("skills"));
    assertEquals("手动填写", ((Map<?, ?>) parsed.get("source")).get("provider"));
    normalizer.ensureProfileSource(parsed, "", Map.of("source-id", "sample"));
    assertEquals(Map.of("source-id", "sample"), ((Map<?, ?>) parsed.get("source")).get("raw"));
    assertTrue(normalizer.renderBossProfileText(parsed, Map.of()).startsWith("# 求职画像"));
  }

  @Test
  void fallbackSummaryUsesTargetsSkillsExclusionsAndFirstSentence() {
    String summary =
        normalizer.fallbackProfileSummary(
            Map.of(
                "name",
                "candidate",
                "years_experience",
                "6年",
                "current_title",
                "developer",
                "job_expectations",
                Map.of("position", "Java", "city", "上海", "salary", "30K", "hard_excludes", "外包"),
                "skills",
                List.of("Java", "Python"),
                "personal_advantage",
                "第一句。第二句。",
                "job_status",
                Map.of("statusDesc", "在职")));
    assertTrue(summary.contains("6年经验"));
    assertTrue(summary.contains("期望城市上海"));
    assertTrue(summary.contains("薪资30K"));
    assertTrue(summary.contains("Java, Python"));
    assertTrue(summary.contains("硬性排除外包"));
    assertFalse(summary.contains("第二句"));
    assertTrue(summary.endsWith("。"));
  }

  @Test
  void fallbackSummaryIsBoundedAndEmptyProfileHasUsefulDefault() {
    String summary =
        normalizer.fallbackProfileSummary(
            Map.of(
                "name",
                "N".repeat(300),
                "skills",
                "S".repeat(300),
                "expected_titles",
                "T".repeat(300)));
    assertEquals(220, summary.length());
    assertTrue(normalizer.fallbackProfileSummary(Map.of()).contains("可结合岗位要求"));
  }
}
