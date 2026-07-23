package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.security.AuthenticationScope;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.auth.service.BossAuthService;
import com.jobbuddy.backend.modules.auth.service.BossCliService;
import com.jobbuddy.backend.modules.chat.service.RuntimeToolClient;
import com.jobbuddy.backend.modules.chat.service.impl.JobRuntimeServiceImpl;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.system.service.SystemSettingsService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JobRuntimeServiceImplTest {

  @BeforeEach
  void bindAuthenticationScope() {
    AuthenticationScope.set("tenant-a", "user-a");
  }

  @AfterEach
  void clearAuthenticationScope() {
    AuthenticationScope.clear();
  }

  @Test
  void recommendJobsFastShouldServeFirstPaintAndFlipFromSinglePagePoolWithoutExtraBossCalls() {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setMaxJobsPerRecommend(2);
    properties.setMaxJobsPerScoring(80);
    properties.setBossSearchMaxPages(2);
    properties.setBossSearchPageDelayMillis(0);
    JsonCodec jsonCodec = new JsonCodec();

    // 真实 Boss 单页返回的岗位数远多于单屏 limit，首屏只抓 1 页即可覆盖首屏切片与下一批换一批切片。
    when(bossCliService.searchJobsFirstPage(any(IntentResult.class)))
        .thenReturn(jobsWithPrefix("p1-", 4));
    when(settingsService.filterBlacklistedJobs(any(List.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    JobRuntimeServiceImpl service =
        new JobRuntimeServiceImpl(
            runtimeToolClient,
            properties,
            bossAuthService,
            jsonCodec,
            bossCliService,
            settingsService);

    Map<String, Object> firstSlots = new LinkedHashMap<String, Object>();
    firstSlots.put("role", "大模型应用开发");
    IntentResult firstIntent =
        new IntentResult(
            "job",
            "job.recommend",
            0.9,
            Collections.<String>emptyList(),
            "low",
            false,
            "call_get_recommend_jobs",
            firstSlots);
    List<Map<String, Object>> firstResult = service.recommendJobsFast(firstIntent, "s1", null);

    // 换一批：同一组检索条件、boss_page=2，命中同一份候选池缓存切片，不再请求 Boss。
    Map<String, Object> flipSlots = new LinkedHashMap<String, Object>();
    flipSlots.put("role", "大模型应用开发");
    flipSlots.put("boss_page", 2);
    IntentResult flipIntent =
        new IntentResult(
            "job",
            "job.recommend",
            0.9,
            Collections.<String>emptyList(),
            "low",
            false,
            "call_get_recommend_jobs",
            flipSlots);
    List<Map<String, Object>> flipResult = service.recommendJobsFast(flipIntent, "s1", null);

    assertEquals(2, firstResult.size());
    assertEquals(2, flipResult.size());
    // 两批切片不重叠，确认换一批刷出的是候选池里的下一批岗位。
    assertNotEquals(firstResult.get(0).get("securityId"), flipResult.get(0).get("securityId"));
    // 首屏只抓 1 页（单次 Boss 请求即出结果）；换一批从同页候选池缓存切片，零额外 Boss 请求。
    verify(bossCliService, times(1)).searchJobsFirstPage(any(IntentResult.class));
    verify(bossCliService, never()).searchJobsPage(any(IntentResult.class), anyInt());
    verify(bossCliService, never()).enrichJobDetails(any(List.class), anyInt());
    // 仅首屏真实抓取记忆一次凭证；换一批命中缓存不触发登录态副作用。
    verify(bossAuthService, times(1))
        .rememberCurrentCredential(any(com.fasterxml.jackson.databind.JsonNode.class));
  }

  @Test
  void recommendJobsFastShouldDropOffSalaryAndInternJobsWhenSalaryRangeGiven() {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setMaxJobsPerRecommend(6);
    properties.setBossSearchMaxPages(1);
    properties.setBossSearchPageDelayMillis(0);

    List<Map<String, Object>> source = new ArrayList<Map<String, Object>>();
    source.add(job("ok", "大模型应用开发工程师", "40-50K"));
    source.add(job("overlap", "大模型平台开发", "45-60K"));
    source.add(job("single", "大模型应用开发", "40K"));
    source.add(job("low", "Java 开发", "17-18K"));
    source.add(job("annualLow", "大模型应用开发", "21-35K·13薪"));
    source.add(job("edgeOverlap", "Java 大模型应用开发", "30-41K"));
    source.add(job("rawYuanLow", "Java 开发", "8000-12000"));
    source.add(job("monthlyYuanLow", "Java 开发", "8000-12000元/月"));
    source.add(job("monthlyYuanOverlap", "大模型应用开发", "35000-50000元/月"));
    source.add(jobWithSalaryBounds("structuredLow", "Java 开发", 8000, 12000));
    source.add(jobWithSalaryBounds("structuredOverlap", "大模型应用开发", 35000, 50000));
    source.add(job("dayIntern", "大模型实习生", "490-500元/天"));
    source.add(job("day", "数据标注", "200-300元/天"));
    source.add(job("negotiable", "大模型应用开发", "面议"));

    when(bossCliService.searchJobsFirstPage(any(IntentResult.class))).thenReturn(source);
    when(settingsService.filterBlacklistedJobs(any(List.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    JobRuntimeServiceImpl service =
        new JobRuntimeServiceImpl(
            runtimeToolClient,
            properties,
            bossAuthService,
            new JsonCodec(),
            bossCliService,
            settingsService);
    Map<String, Object> slots = new LinkedHashMap<String, Object>();
    slots.put("role", "大模型应用开发");
    slots.put("salary_min_k", 40);
    slots.put("salary_max_k", 50);
    IntentResult intent =
        new IntentResult(
            "job",
            "job.recommend",
            0.9,
            Collections.<String>emptyList(),
            "low",
            false,
            "call_get_recommend_jobs",
            slots);

    List<Map<String, Object>> result = service.recommendJobsFast(intent, "s1", null);

    java.util.Set<Object> keptIds = new java.util.HashSet<Object>();
    for (Map<String, Object> row : result) keptIds.add(row.get("securityId"));
    assertEquals(5, result.size());
    assertTrue(keptIds.contains("ok"));
    assertTrue(keptIds.contains("overlap"));
    assertTrue(keptIds.contains("single"));
    assertTrue(keptIds.contains("monthlyYuanOverlap"));
    assertTrue(keptIds.contains("structuredOverlap"));
    assertFalse(keptIds.contains("negotiable"));
    assertTrue(!keptIds.contains("low"));
    assertFalse(keptIds.contains("annualLow"));
    assertFalse(keptIds.contains("edgeOverlap"));
    assertTrue(!keptIds.contains("rawYuanLow"));
    assertTrue(!keptIds.contains("monthlyYuanLow"));
    assertTrue(!keptIds.contains("structuredLow"));
    assertTrue(!keptIds.contains("day"));
    assertTrue(!keptIds.contains("dayIntern"));
  }

  @Test
  void recommendJobsFastShouldRejectUnsupportedSpecialtyFromProfileEvidence() {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    when(bossCliService.searchJobsFirstPage(any(IntentResult.class)))
        .thenReturn(
            java.util.Arrays.asList(
                job("fit", "Java RAG 大模型应用开发工程师", "40-50K"),
                job("multimodal", "多模态大模型应用算法工程师", "40-50K")));
    when(settingsService.filterBlacklistedJobs(any(List.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setMaxJobsPerRecommend(10);
    JobRuntimeServiceImpl service =
        new JobRuntimeServiceImpl(
            runtimeToolClient,
            properties,
            bossAuthService,
            new JsonCodec(),
            bossCliService,
            settingsService);
    Map<String, Object> slots = new LinkedHashMap<String, Object>();
    slots.put("role", "Java 大模型应用开发");
    slots.put("include_keywords", java.util.Arrays.asList("Java", "RAG", "Agent", "Spring Cloud"));

    List<Map<String, Object>> result =
        service.recommendJobsFast(
            new IntentResult(
                "job",
                "job.recommend",
                0.99,
                Collections.<String>emptyList(),
                "low",
                false,
                "call_get_recommend_jobs",
                slots),
            "s1",
            null);

    assertEquals(1, result.size());
    assertEquals("fit", result.get(0).get("securityId"));
  }

  @Test
  void recommendJobsFastShouldUseRealSearch() {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setMaxJobsPerRecommend(2);
    List<Map<String, Object>> sourceJobs = jobs(2);
    when(bossCliService.searchJobsFirstPage(any(IntentResult.class))).thenReturn(sourceJobs);
    when(settingsService.filterBlacklistedJobs(any(List.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    JobRuntimeServiceImpl service =
        new JobRuntimeServiceImpl(
            runtimeToolClient,
            properties,
            bossAuthService,
            new JsonCodec(),
            bossCliService,
            settingsService);
    Map<String, Object> slots = new LinkedHashMap<String, Object>();
    slots.put("role", "大模型应用开发");
    IntentResult intent =
        new IntentResult(
            "job",
            "job.recommend",
            0.9,
            Collections.<String>emptyList(),
            "low",
            false,
            "call_get_recommend_jobs",
            slots);

    List<Map<String, Object>> result = service.recommendJobsFast(intent, "s1", null);

    assertEquals(2, result.size());
    verify(bossCliService).searchJobsFirstPage(any(IntentResult.class));
  }

  @Test
  void fastSearchCacheMustBeIsolatedByTenantAndUser() {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    when(bossCliService.searchJobsFirstPage(any(IntentResult.class)))
        .thenReturn(jobsWithPrefix("owner-a-", 2), jobsWithPrefix("owner-b-", 2));
    when(settingsService.filterBlacklistedJobs(any(List.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setMaxJobsPerRecommend(2);
    JobRuntimeServiceImpl service =
        new JobRuntimeServiceImpl(
            runtimeToolClient,
            properties,
            bossAuthService,
            new JsonCodec(),
            bossCliService,
            settingsService);
    Map<String, Object> slots = new LinkedHashMap<String, Object>();
    slots.put("role", "上海 Java 大模型应用开发");
    IntentResult intent =
        new IntentResult(
            "job",
            "job.recommend",
            0.9,
            Collections.<String>emptyList(),
            "low",
            false,
            "call_get_recommend_jobs",
            slots);

    List<Map<String, Object>> ownerA = service.recommendJobsFast(intent, "s-a", null);
    AuthenticationScope.set("tenant-a", "user-b");
    List<Map<String, Object>> ownerB = service.recommendJobsFast(intent, "s-b", null);

    assertTrue(String.valueOf(ownerA.get(0).get("securityId")).startsWith("owner-a-"));
    assertTrue(String.valueOf(ownerB.get(0).get("securityId")).startsWith("owner-b-"));
    verify(bossCliService, times(2)).searchJobsFirstPage(any(IntentResult.class));
  }

  @Test
  void bossCandidatePoolTimeoutShouldExceedBoundedToolRetryBudget() {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    JobRuntimeServiceImpl service =
        new JobRuntimeServiceImpl(
            runtimeToolClient,
            new JobBuddyProperties(),
            bossAuthService,
            new JsonCodec(),
            bossCliService,
            settingsService);

    // agent-tool 默认 20 秒 × 2 次尝试，Java 首屏还需预留退避、限速和转发余量。
    assertTrue(service.bossCandidatePoolTimeoutSeconds() >= 50);
  }

  @Test
  @SuppressWarnings("unchecked")
  void matchResumeShouldKeepOnlyJobsMeetingMinimumRecommendedScore() {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setMinimumRecommendedMatchScore(70);
    when(runtimeToolClient.invoke(any(String.class), any(Map.class), any(String.class), any()))
        .thenReturn(runtimeMatch(matchRow("high", 82), matchRow("low", 65)));
    JobRuntimeServiceImpl service =
        new JobRuntimeServiceImpl(
            runtimeToolClient,
            properties,
            bossAuthService,
            new JsonCodec(),
            bossCliService,
            settingsService);

    Map<String, Object> result =
        service.matchResume(
            parsedResume(), java.util.Arrays.asList(realJob("high"), realJob("low")), "s1");
    List<Map<String, Object>> matches = (List<Map<String, Object>>) result.get("matches");

    assertEquals(1, matches.size());
    assertEquals("high", matches.get(0).get("id"));
    assertEquals(70, result.get("minimum_recommended_match_score"));
    assertEquals(1, result.get("recommended_count"));
    assertEquals(true, result.get("recommendation_threshold_applied"));
    assertFalse(Boolean.TRUE.equals(result.get("recommendation_threshold_relaxed")));
  }

  @Test
  @SuppressWarnings("unchecked")
  void matchResumeShouldKeepHighestScoreWhenNoJobMeetsMinimum() {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setMinimumRecommendedMatchScore(90);
    when(runtimeToolClient.invoke(any(String.class), any(Map.class), any(String.class), any()))
        .thenReturn(runtimeMatch(matchRow("best", 80), matchRow("other", 70)));
    JobRuntimeServiceImpl service =
        new JobRuntimeServiceImpl(
            runtimeToolClient,
            properties,
            bossAuthService,
            new JsonCodec(),
            bossCliService,
            settingsService);

    Map<String, Object> result =
        service.matchResume(
            parsedResume(), java.util.Arrays.asList(realJob("best"), realJob("other")), "s1");
    List<Map<String, Object>> matches = (List<Map<String, Object>>) result.get("matches");

    assertEquals(1, matches.size());
    assertEquals("best", matches.get(0).get("id"));
    assertEquals(true, result.get("recommendation_threshold_relaxed"));
    assertTrue(String.valueOf(result.get("warnings")).contains("匹配度最高"));
  }

  @Test
  void prequalifyRecommendationsShouldRejectLowScoreLowConfidenceAndNegativeAdvice() {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setMinimumRecommendedMatchScore(70);
    Map<String, Object> accepted = recommendationMatch("accepted", 82, "medium", "推荐");
    Map<String, Object> lowScore = recommendationMatch("low-score", 55, "medium", "可尝试");
    Map<String, Object> lowConfidence = recommendationMatch("low-confidence", 84, "low", "推荐");
    Map<String, Object> negative = recommendationMatch("negative", 88, "high", "不建议");
    when(runtimeToolClient.invoke(any(String.class), any(Map.class), any(String.class), any()))
        .thenReturn(runtimeMatch(accepted, lowScore, lowConfidence, negative));
    JobRuntimeServiceImpl service =
        new JobRuntimeServiceImpl(
            runtimeToolClient,
            properties,
            bossAuthService,
            new JsonCodec(),
            bossCliService,
            settingsService);

    com.jobbuddy.backend.modules.chat.service.JobRecommendationResult result =
        service.prequalifyRecommendations(
            parsedResume(),
            java.util.Arrays.asList(
                realJob("accepted"),
                realJob("low-score"),
                realJob("low-confidence"),
                realJob("negative")),
            "s1");

    assertEquals(1, result.getQualifiedCount());
    assertEquals("accepted", result.getJobs().get(0).get("securityId"));
    assertEquals(82, result.getJobs().get(0).get("matchScore"));
    assertEquals("medium", result.getJobs().get(0).get("matchConfidence"));
    assertTrue(result.getRejectionReasons().containsKey("未达到最低匹配分"));
    assertTrue(result.getRejectionReasons().containsKey("匹配置信度低"));
    assertTrue(result.getRejectionReasons().containsKey("投递建议为不建议"));
  }

  @Test
  void matchResumeShouldRejectFixtureEvidenceBeforeCallingRuntimeTool() {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    JobRuntimeServiceImpl service =
        new JobRuntimeServiceImpl(
            runtimeToolClient,
            new JobBuddyProperties(),
            bossAuthService,
            new JsonCodec(),
            bossCliService,
            settingsService);
    ResumeRecord resume = new ResumeRecord();
    Map<String, Object> parsed = new LinkedHashMap<String, Object>();
    parsed.put("skills", Collections.singletonList("Java"));
    resume.setParsed(parsed);
    Map<String, Object> job = new LinkedHashMap<String, Object>();
    job.put("source", "fixture");
    job.put("jobName", "Java 工程师");

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.matchResume(resume, Collections.singletonList(job), "s1"));

    assertTrue(error.getMessage().contains("来源无效"));
    verify(runtimeToolClient, never())
        .invoke(any(String.class), any(Map.class), any(String.class), any(String.class));
  }

  private ResumeRecord parsedResume() {
    ResumeRecord resume = new ResumeRecord();
    Map<String, Object> parsed = new LinkedHashMap<String, Object>();
    parsed.put("skills", Collections.singletonList("Java"));
    resume.setParsed(parsed);
    return resume;
  }

  private Map<String, Object> realJob(String id) {
    Map<String, Object> job = new LinkedHashMap<String, Object>();
    job.put("securityId", id);
    job.put("source", "boss");
    job.put("jobName", "上海 Java 大模型应用开发岗");
    job.put("salaryDesc", "40-50K");
    return job;
  }

  private Map<String, Object> matchRow(String id, int score) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("id", id);
    row.put("score", score);
    row.put("evidence", Collections.singletonList("岗位要求与简历技术栈一致"));
    return row;
  }

  private Map<String, Object> recommendationMatch(
      String id, int score, String confidence, String recommendation) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("id", id);
    row.put("score", score);
    row.put("score_confidence", confidence);
    row.put("recommendation", recommendation);
    row.put("hits", Collections.singletonList("Java、RAG 与 Agent 能力匹配岗位要求"));
    return row;
  }

  private Map<String, Object> runtimeMatch(Map<String, Object>... matches) {
    Map<String, Object> output = new LinkedHashMap<String, Object>();
    output.put("matches", java.util.Arrays.asList(matches));
    output.put("scored_count", matches.length);
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("success", true);
    result.put("output", output);
    return result;
  }

  private List<Map<String, Object>> jobs(int count) {
    List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < count; i++) {
      Map<String, Object> row = new LinkedHashMap<String, Object>();
      row.put("securityId", "sid" + i);
      row.put("jobName", "Java 大模型应用开发工程师 " + i);
      row.put("salaryDesc", "40-50K");
      rows.add(row);
    }
    return rows;
  }

  private List<Map<String, Object>> jobsWithPrefix(String prefix, int count) {
    List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < count; i++) {
      rows.add(job(prefix + i, "大模型应用开发 " + prefix + i, "40-50K"));
    }
    return rows;
  }

  private Map<String, Object> job(String securityId, String jobName, String salaryDesc) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("securityId", securityId);
    row.put("jobName", jobName);
    row.put("salaryDesc", salaryDesc);
    return row;
  }

  private Map<String, Object> jobWithSalaryBounds(
      String securityId, String jobName, int lowSalary, int highSalary) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("securityId", securityId);
    row.put("jobName", jobName);
    row.put("lowSalary", lowSalary);
    row.put("highSalary", highSalary);
    return row;
  }
}
