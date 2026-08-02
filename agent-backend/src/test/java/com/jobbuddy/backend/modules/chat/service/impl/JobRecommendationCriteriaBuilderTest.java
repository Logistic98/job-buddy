package com.jobbuddy.backend.modules.chat.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import com.jobbuddy.backend.modules.prompt.model.PersonalContext;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 验证 JobRecommendationCriteriaBuilder 的核心行为、异常路径与边界条件。
 */
class JobRecommendationCriteriaBuilderTest {

  /**
   * 验证 JobRecommendationCriteriaBuilder 中简历的检索、筛选与排序规则。
   */
  @Test
  void shouldRecoverSalaryFromRawMessageAndMergeProfileResumeEvidence() {
    Map<String, Object> expectations = new LinkedHashMap<String, Object>();
    expectations.put("city", "杭州");
    expectations.put("salary", "20-25K");
    expectations.put("hard_excludes", java.util.Arrays.asList("长期夜班", "频繁出差"));
    Map<String, Object> profile = new LinkedHashMap<String, Object>();
    profile.put("skills", java.util.Arrays.asList("Java", "Kubernetes"));
    profile.put("years_experience", 5);
    profile.put("job_expectations", expectations);
    Map<String, Object> resume = new LinkedHashMap<String, Object>();
    resume.put("skills", java.util.Arrays.asList("Spring Cloud", "Docker"));
    PersonalContext context = context(profile, resume);
    Map<String, Object> slots = new LinkedHashMap<String, Object>();
    slots.put("role", "云原生后端开发");
    IntentResult intent = intent(slots);

    IntentResult result =
        JobRecommendationCriteriaBuilder.enrich(intent, context, "筛选杭州云原生后端开发 20-30K 岗位");

    assertEquals(20, result.getSlots().get("salary_min_k"));
    assertEquals(30, result.getSlots().get("salary_max_k"));
    assertEquals(true, result.getSlots().get("salary_strict"));
    assertEquals(5, result.getSlots().get("candidate_years_experience"));
    assertTrue(((List<?>) result.getSlots().get("include_keywords")).contains("Java"));
    assertTrue(((List<?>) result.getSlots().get("include_keywords")).contains("Docker"));
    assertTrue(((List<?>) result.getSlots().get("hard_excludes")).contains("长期夜班"));
  }

  /**
   * 验证 JobRecommendationCriteriaBuilder 的检索、筛选与排序规则。
   */
  @Test
  void shouldParseCommonMonthlySalaryExpressions() {
    assertRange("月薪 4-5 万", 40, 50);
    assertRange("40000-50000 元/月", 40, 50);
    assertRange("45k-55k", 45, 55);
    int[] minimum = JobRecommendationCriteriaBuilder.parseSalaryRangeK("40K以上");
    assertEquals(40, minimum[0]);
    assertEquals(0, minimum[1]);
  }

  /**
   * 画像编辑器会用顿号串联排除项，补全槽位时必须还原为独立词条。
   */
  @Test
  void shouldSplitEnumerationCommaSeparatedExcludes() {
    Map<String, Object> expectations = new LinkedHashMap<String, Object>();
    expectations.put("hard_excludes", "外包、劳务派遣、驻场、OD");
    Map<String, Object> profile = new LinkedHashMap<String, Object>();
    profile.put("job_expectations", expectations);

    IntentResult result =
        JobRecommendationCriteriaBuilder.enrich(
            intent(Collections.<String, Object>emptyMap()),
            context(profile, Collections.<String, Object>emptyMap()),
            "筛选岗位");

    List<?> excludes = (List<?>) result.getSlots().get("hard_excludes");
    assertEquals(java.util.Arrays.asList("外包", "劳务派遣", "驻场", "OD"), excludes);
  }

  /**
   * 断言薪资范围上下限。
   *
   * @param value 待处理值
   * @param min 最小值
   * @param max 上限值
   */
  private void assertRange(String value, int min, int max) {
    int[] range = JobRecommendationCriteriaBuilder.parseSalaryRangeK(value);
    assertEquals(min, range[0]);
    assertEquals(max, range[1]);
  }

  /**
   * 验证意图。
   *
   * @param slots 槽位
   * @return 测试意图
   */
  private IntentResult intent(Map<String, Object> slots) {
    return new IntentResult(
        "job",
        "job.recommend",
        0.99,
        Collections.<String>emptyList(),
        "low",
        false,
        "call_get_recommend_jobs",
        slots);
  }

  /**
   * 验证上下文。
   *
   * @param profile 画像
   * @param resume 简历
   * @return 测试任务上下文
   */
  private PersonalContext context(Map<String, Object> profile, Map<String, Object> resume) {
    return new PersonalContext(
        "job.recommend",
        profile,
        resume,
        Collections.<Map<String, Object>>emptyList(),
        Collections.<Map<String, Object>>emptyList(),
        Collections.<Map<String, Object>>emptyList(),
        Collections.<Map<String, Object>>emptyList(),
        Collections.<Map<String, Object>>emptyList(),
        "");
  }
}
