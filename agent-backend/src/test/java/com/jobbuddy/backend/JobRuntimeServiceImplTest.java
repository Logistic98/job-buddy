package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeToolArguments;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeToolResult;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 验证 JobRuntimeServiceImpl 的核心行为、异常路径与边界条件。
 */
class JobRuntimeServiceImplTest {
  private static final JsonCodec JSON = new JsonCodec();

  /**
   * 绑定测试认证作用域。
   */
  @BeforeEach
  void bindAuthenticationScope() {
    AuthenticationScope.set("tenant-a", "user-a");
  }

  /**
   * 验证 JobRuntimeServiceImpl 中认证的身份认证与会话边界。
   */
  @AfterEach
  void clearAuthenticationScope() {
    AuthenticationScope.clear();
  }

  /**
   * 默认推荐预算应优先快速返回一屏结果，并把更大候选池交给“换一批”按需消费。
   */
  @Test
  void defaultRecommendationBudgetShouldFavorFastFirstBatch() {
    JobBuddyProperties properties = new JobBuddyProperties();

    assertEquals(5, properties.getMaxJobsPerRecommend());
    assertEquals(3, properties.getRecommendOverfetchFactor());
    assertEquals(30, properties.getMaxJobsPerScoring());
    assertEquals(15, properties.getBossSearchTargetCandidates());
    assertEquals(3000, properties.getBossSearchPageDelayMillis());
  }

  /**
   * 验证 JobRuntimeServiceImpl 中岗位的检索、筛选与排序规则。
   */
  @Test
  void recommendJobsFastShouldServeFirstPaintAndFlipFromSinglePagePoolWithoutExtraBossCalls() {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setMaxJobsPerRecommend(2);
    properties.setRecommendOverfetchFactor(1);
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
    firstSlots.put("role", "云原生平台开发");
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
    flipSlots.put("role", "云原生平台开发");
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

  /**
   * 验证 JobRuntimeServiceImpl 中岗位的检索、筛选与排序规则。
   */
  @Test
  void recommendJobsFastShouldTopUpOnePageWhenFilteringLeavesNoRecommendationReserve() {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setMaxJobsPerRecommend(4);
    properties.setRecommendOverfetchFactor(1);
    properties.setMaxJobsPerScoring(80);
    properties.setBossSearchMaxPages(2);
    properties.setBossSearchMaxPageDepth(3);
    properties.setBossSearchPageDelayMillis(0);

    when(bossCliService.searchJobsFirstPage(any(IntentResult.class)))
        .thenReturn(jobsWithPrefix("p1-", 2));
    when(bossCliService.searchJobsPage(any(IntentResult.class), anyInt()))
        .thenReturn(jobsWithPrefix("p2-", 3));
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
    slots.put("role", "云原生平台开发");
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

    assertEquals(4, result.size());
    assertTrue(
        result.stream().anyMatch(row -> String.valueOf(row.get("securityId")).startsWith("p2-")));
    verify(bossCliService, times(1)).searchJobsFirstPage(any(IntentResult.class));
    verify(bossCliService, times(1)).searchJobsPage(any(IntentResult.class), anyInt());
  }

  /**
   * 验证 JobRuntimeServiceImpl 中岗位的检索、筛选与排序规则。
   */
  @Test
  void recommendJobsFastShouldUseSecondReservePageWhenPreviousPageAddsNoCandidates() {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setMaxJobsPerRecommend(4);
    properties.setRecommendOverfetchFactor(1);
    properties.setMaxJobsPerScoring(80);
    properties.setBossSearchMaxPages(3);
    properties.setBossSearchMaxPageDepth(3);
    properties.setBossSearchPageDelayMillis(0);

    when(bossCliService.searchJobsFirstPage(any(IntentResult.class)))
        .thenReturn(jobsWithPrefix("p1-", 2));
    when(bossCliService.searchJobsPage(any(IntentResult.class), anyInt()))
        .thenReturn(jobsWithPrefix("p1-", 2), jobsWithPrefix("p3-", 3));
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
    slots.put("role", "云原生平台开发");
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

    assertEquals(4, result.size());
    assertTrue(
        result.stream().anyMatch(row -> String.valueOf(row.get("securityId")).startsWith("p3-")));
    verify(bossCliService, times(1)).searchJobsFirstPage(any(IntentResult.class));
    verify(bossCliService, times(2)).searchJobsPage(any(IntentResult.class), anyInt());
  }

  /**
   * 验证 JobRuntimeServiceImpl 中岗位的检索、筛选与排序规则。
   */
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
    source.add(job("ok", "云原生平台开发工程师", "25-35K"));
    source.add(job("overlap", "云平台开发", "30-45K"));
    source.add(job("single", "云原生平台开发", "30K"));
    source.add(job("low", "Go 开发", "12-14K"));
    source.add(job("annualLow", "云原生平台开发", "14-22K·13薪"));
    source.add(job("edgeOverlap", "Go 云原生平台开发", "20-26K"));
    source.add(job("rawYuanLow", "Java 开发", "8000-12000"));
    source.add(job("monthlyYuanLow", "Java 开发", "8000-12000元/月"));
    source.add(job("monthlyYuanOverlap", "云原生平台开发", "22000-38000元/月"));
    source.add(jobWithSalaryBounds("structuredLow", "Java 开发", 8000, 12000));
    source.add(jobWithSalaryBounds("structuredOverlap", "云原生平台开发", 22000, 38000));
    source.add(job("dayIntern", "云平台实习生", "490-500元/天"));
    source.add(job("day", "数据标注", "200-300元/天"));
    source.add(job("negotiable", "云原生平台开发", "面议"));

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
    slots.put("role", "云原生平台开发");
    slots.put("salary_min_k", 25);
    slots.put("salary_max_k", 35);
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

  /**
   * 验证 JobRuntimeServiceImpl 中岗位的输入校验与拒绝边界。
   */
  @Test
  void recommendJobsFastShouldRejectUnsupportedSpecialtyFromProfileEvidence() {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    when(bossCliService.searchJobsFirstPage(any(IntentResult.class)))
        .thenReturn(
            java.util.Arrays.asList(
                job("fit", "Go Kubernetes 云原生平台开发工程师", "25-35K"),
                job("multimodal", "计算机视觉算法工程师", "25-35K")));
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
    slots.put("role", "Go 云原生平台开发");
    slots.put("include_keywords", java.util.Arrays.asList("Go", "Kubernetes", "PostgreSQL", "微服务"));

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

  /**
   * 短 ASCII 硬排除词只检查用户可见的岗位内容，不得命中随机令牌、URL 或普通英文单词片段。
   */
  @Test
  void recommendJobsFastShouldNotMatchOdInsideOpaqueMetadata() {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    Map<String, Object> valid = job("token-model-product", "大模型应用开发工程师", "40-60K");
    valid.put("originalUrl", "https://example.test/product/model/token");
    valid.put("brandLogo", "https://example.test/upload/model.png");
    Map<String, Object> excluded = job("clean-token", "大模型应用 OD 开发工程师", "40-60K");
    when(bossCliService.searchJobsFirstPage(any(IntentResult.class)))
        .thenReturn(java.util.Arrays.asList(valid, excluded));
    when(settingsService.filterBlacklistedJobs(any(List.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    JobRuntimeServiceImpl service =
        new JobRuntimeServiceImpl(
            runtimeToolClient,
            new JobBuddyProperties(),
            bossAuthService,
            new JsonCodec(),
            bossCliService,
            settingsService);
    Map<String, Object> slots = new LinkedHashMap<String, Object>();
    slots.put("role", "大模型应用开发");
    slots.put("hard_excludes", Collections.singletonList("OD"));

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
    assertEquals("token-model-product", result.get(0).get("securityId"));
  }

  /**
   * 验证 JobRuntimeServiceImpl 中岗位的检索、筛选与排序规则。
   */
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
    slots.put("role", "云原生平台开发");
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

  /**
   * 验证 JobRuntimeServiceImpl 中岗位的检索、筛选与排序规则。
   */
  @Test
  void recommendJobsFastShouldOverfetchCandidatesAndNeverWrapConsumedRows() {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setMaxJobsPerRecommend(2);
    properties.setRecommendOverfetchFactor(3);
    properties.setMaxJobsPerScoring(10);
    properties.setBossSearchMaxPages(1);
    properties.setBossSearchPageDelayMillis(0);
    when(bossCliService.searchJobsFirstPage(any(IntentResult.class)))
        .thenReturn(jobsWithPrefix("p1-", 6));
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
    slots.put("role", "云原生平台开发");
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

    List<Map<String, Object>> first = service.recommendJobsFast(intent, "s1", null);
    Map<String, Object> consumedSlots = new LinkedHashMap<String, Object>(slots);
    consumedSlots.put("boss_page", 2);
    consumedSlots.put("candidate_offset", 6);
    IntentResult consumedIntent =
        new IntentResult(
            "job",
            "job.recommend",
            1.0,
            Collections.<String>emptyList(),
            "low",
            false,
            "call_get_recommend_jobs",
            consumedSlots);
    List<Map<String, Object>> exhausted = service.recommendJobsFast(consumedIntent, "s1", null);

    assertEquals(6, first.size());
    assertTrue(exhausted.isEmpty());
    verify(bossCliService, times(1)).searchJobsPage(any(IntentResult.class), anyInt());
  }

  /**
   * 验证换一批补页达到原始候选目标后提前停止，避免固定抓满单批页数。
   */
  @Test
  void recommendJobsFastShouldStopBatchAfterCandidateTargetIsReached() {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setMaxJobsPerRecommend(2);
    properties.setRecommendOverfetchFactor(3);
    properties.setMaxJobsPerScoring(30);
    properties.setBossSearchMaxPages(3);
    properties.setBossSearchMaxPageDepth(15);
    properties.setBossSearchPageDelayMillis(0);
    when(bossCliService.searchJobsFirstPage(any(IntentResult.class)))
        .thenReturn(jobsWithPrefix("p1-", 6));
    when(bossCliService.searchJobsPage(any(IntentResult.class), anyInt()))
        .thenReturn(jobsWithPrefix("p2-", 15));
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
    slots.put("role", "云原生平台开发");
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

    service.recommendJobsFast(intent, "s1", null);
    Map<String, Object> nextSlots = new LinkedHashMap<String, Object>(slots);
    nextSlots.put("boss_page", 2);
    nextSlots.put("candidate_offset", 6);
    IntentResult nextIntent =
        new IntentResult(
            "job",
            "job.recommend",
            1.0,
            Collections.<String>emptyList(),
            "low",
            false,
            "call_get_recommend_jobs",
            nextSlots);

    List<Map<String, Object>> next = service.recommendJobsFast(nextIntent, "s1", null);

    assertEquals(6, next.size());
    verify(bossCliService, times(1)).searchJobsPage(any(IntentResult.class), anyInt());
  }

  /**
   * 验证单次抓取页数包含首屏页，默认三页预算不会额外预热第四页。
   */
  @Test
  void recommendJobsFastShouldCountFirstPageAgainstSingleFetchBudget() {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setMaxJobsPerRecommend(10);
    properties.setRecommendOverfetchFactor(3);
    properties.setMaxJobsPerScoring(30);
    properties.setBossSearchMaxPages(3);
    properties.setBossSearchMaxPageDepth(15);
    properties.setBossSearchPageDelayMillis(0);
    when(bossCliService.searchJobsFirstPage(any(IntentResult.class)))
        .thenReturn(jobsWithPrefix("p1-", 1));
    when(bossCliService.searchJobsPage(any(IntentResult.class), anyInt()))
        .thenAnswer(
            invocation -> jobsWithPrefix("p" + invocation.getArgument(1, Integer.class) + "-", 1));
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
    slots.put("role", "云原生平台开发");
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

    service.recommendJobsFast(intent, "s1", null);

    verify(bossCliService, times(1)).searchJobsFirstPage(any(IntentResult.class));
    verify(bossCliService, times(2)).searchJobsPage(any(IntentResult.class), anyInt());
  }

  /**
   * 验证 JobRuntimeServiceImpl 中用户的权限与租户隔离边界。
   */
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
    slots.put("role", "杭州 Go 云原生平台开发");
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

  /**
   * 同一用户、同一条件的并发缓存未命中只允许一个请求访问 Boss，其他请求等待并复用候选池。
   *
   * @throws Exception 并发测试失败时抛出
   */
  @Test
  void recommendJobsFastShouldCoalesceConcurrentCacheMisses() throws Exception {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setMaxJobsPerRecommend(2);
    properties.setRecommendOverfetchFactor(1);
    properties.setBossSearchMaxPages(1);
    properties.setBossSearchPageDelayMillis(0);
    CountDownLatch firstSearchStarted = new CountDownLatch(1);
    CountDownLatch duplicateSearchStarted = new CountDownLatch(1);
    CountDownLatch releaseSearch = new CountDownLatch(1);
    AtomicInteger searchCalls = new AtomicInteger();
    when(bossCliService.searchJobsFirstPage(any(IntentResult.class)))
        .thenAnswer(
            invocation -> {
              if (searchCalls.incrementAndGet() == 1) {
                firstSearchStarted.countDown();
              } else {
                duplicateSearchStarted.countDown();
              }
              releaseSearch.await(2, TimeUnit.SECONDS);
              return jobsWithPrefix("shared-", 3);
            });
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
    slots.put("role", "云原生平台开发");
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

    CompletableFuture<List<Map<String, Object>>> first =
        CompletableFuture.supplyAsync(() -> recommendWithScope(service, intent, "s1"));
    assertTrue(firstSearchStarted.await(1, TimeUnit.SECONDS));
    CompletableFuture<List<Map<String, Object>>> second =
        CompletableFuture.supplyAsync(() -> recommendWithScope(service, intent, "s2"));
    boolean duplicateStarted = duplicateSearchStarted.await(250, TimeUnit.MILLISECONDS);
    releaseSearch.countDown();
    List<Map<String, Object>> firstResult = first.get(2, TimeUnit.SECONDS);
    List<Map<String, Object>> secondResult = second.get(2, TimeUnit.SECONDS);

    assertFalse(duplicateStarted, "并发缓存未命中不应重复访问 Boss");
    assertEquals(1, searchCalls.get());
    assertEquals(firstResult, secondResult);
  }

  /**
   * 同条件的并发换一批在等待首屏候选池后，仍应按自己的消费游标继续补页。
   *
   * @throws Exception 并发测试失败时抛出
   */
  @Test
  void concurrentFlipShouldTopUpAfterWaitingForSmallerCandidatePool() throws Exception {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setMaxJobsPerRecommend(2);
    properties.setRecommendOverfetchFactor(1);
    properties.setBossSearchMaxPages(1);
    properties.setBossSearchMaxPageDepth(3);
    properties.setBossSearchPageDelayMillis(0);
    CountDownLatch firstSearchStarted = new CountDownLatch(1);
    CountDownLatch releaseFirstSearch = new CountDownLatch(1);
    when(bossCliService.searchJobsFirstPage(any(IntentResult.class)))
        .thenAnswer(
            invocation -> {
              firstSearchStarted.countDown();
              releaseFirstSearch.await(2, TimeUnit.SECONDS);
              return jobsWithPrefix("first-", 2);
            });
    when(bossCliService.searchJobsPage(any(IntentResult.class), eq(2)))
        .thenReturn(jobsWithPrefix("second-", 2));
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
    Map<String, Object> firstSlots = new LinkedHashMap<String, Object>();
    firstSlots.put("role", "云原生平台开发");
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
    Map<String, Object> flipSlots = new LinkedHashMap<String, Object>(firstSlots);
    flipSlots.put("candidate_offset", 2);
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

    CompletableFuture<List<Map<String, Object>>> first =
        CompletableFuture.supplyAsync(() -> recommendWithScope(service, firstIntent, "s1"));
    assertTrue(firstSearchStarted.await(1, TimeUnit.SECONDS));
    CompletableFuture<List<Map<String, Object>>> flip =
        CompletableFuture.supplyAsync(() -> recommendWithScope(service, flipIntent, "s2"));
    releaseFirstSearch.countDown();
    List<Map<String, Object>> firstResult = first.get(2, TimeUnit.SECONDS);
    List<Map<String, Object>> flipResult = flip.get(2, TimeUnit.SECONDS);

    assertEquals(2, firstResult.size());
    assertEquals(2, flipResult.size());
    assertTrue(
        flipResult.stream()
            .allMatch(row -> String.valueOf(row.get("securityId")).startsWith("second-")));
    verify(bossCliService, times(1)).searchJobsFirstPage(any(IntentResult.class));
    verify(bossCliService, times(1)).searchJobsPage(any(IntentResult.class), eq(2));
  }

  /**
   * 验证 JobRuntimeServiceImpl 中工具的失败恢复、超时与降级边界。
   */
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

  private static List<Map<String, Object>> recommendWithScope(
      JobRuntimeServiceImpl service, IntentResult intent, String sessionId) {
    AuthenticationScope.set("tenant-a", "user-a");
    try {
      return service.recommendJobsFast(intent, sessionId, null);
    } finally {
      AuthenticationScope.clear();
    }
  }

  /**
   * 验证 JobRuntimeServiceImpl 中简历的检索、筛选与排序规则。
   */
  @Test
  @SuppressWarnings("unchecked")
  void matchResumeShouldKeepOnlyJobsMeetingMinimumRecommendedScore() {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setMinimumRecommendedMatchScore(70);
    when(runtimeToolClient.invoke(
            any(String.class), any(RuntimeToolArguments.class), any(String.class), any()))
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

  /**
   * 验证 JobRuntimeServiceImpl 中简历的检索、筛选与排序规则。
   */
  @Test
  @SuppressWarnings("unchecked")
  void matchResumeShouldKeepHighestScoreWhenNoJobMeetsMinimum() {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setMinimumRecommendedMatchScore(90);
    when(runtimeToolClient.invoke(
            any(String.class), any(RuntimeToolArguments.class), any(String.class), any()))
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

  /**
   * 验证 JobRuntimeServiceImpl 中简历的检索、筛选与排序规则。
   */
  @Test
  @SuppressWarnings("unchecked")
  void matchResumeSectionsShouldNotInferConfidenceForNonEvidencePartialResult() {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    Map<String, Object> partial = new LinkedHashMap<String, Object>();
    partial.put("id", "partial");
    partial.put("risks", Collections.singletonList("缺少量化领域经验"));
    partial.put(
        "dimensions",
        Collections.singletonMap(
            "technical_skill", Collections.singletonMap("score", Integer.valueOf(85))));
    when(runtimeToolClient.invoke(
            any(String.class), any(RuntimeToolArguments.class), any(String.class), any()))
        .thenReturn(runtimeMatch(partial));
    JobRuntimeServiceImpl service =
        new JobRuntimeServiceImpl(
            runtimeToolClient,
            new JobBuddyProperties(),
            bossAuthService,
            new JsonCodec(),
            bossCliService,
            settingsService);

    Map<String, Object> result =
        service.matchResumeSections(
            parsedResume(),
            Collections.singletonList(realJob("partial")),
            "s1",
            java.util.Arrays.asList("dimensions", "risks", "limitations"));
    List<Map<String, Object>> matches = (List<Map<String, Object>>) result.get("matches");

    assertEquals(1, matches.size());
    assertFalse(matches.get(0).containsKey("score_confidence"));
    assertFalse(matches.get(0).containsKey("evidence_count"));
  }

  /**
   * 单岗位列表证据分析必须显式选择 recommendation_list，不能要求先加载完整 JD。
   */
  @Test
  void matchResumeListEvidenceSectionsShouldUseRecommendationListMode() {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    Map<String, Object> row = recommendationMatch("list-job", 68, "medium", "可尝试");
    when(runtimeToolClient.invoke(
            any(String.class), any(RuntimeToolArguments.class), any(String.class), any()))
        .thenReturn(runtimeMatch(row));
    JobRuntimeServiceImpl service =
        new JobRuntimeServiceImpl(
            runtimeToolClient,
            new JobBuddyProperties(),
            bossAuthService,
            new JsonCodec(),
            bossCliService,
            settingsService);
    Map<String, Object> job = new LinkedHashMap<String, Object>();
    job.put("securityId", "list-job");
    job.put("jobName", "Agent 平台开发工程师");
    job.put("skills", java.util.Arrays.asList("Java", "Agent"));

    service.matchResumeListEvidenceSections(
        parsedResume(), Collections.singletonList(job), "s-list", Collections.<String>emptyList());

    verify(runtimeToolClient)
        .invoke(
            eq("resume_match"),
            argThat(args -> "recommendation_list".equals(args.get("evaluation_mode").asText())),
            eq("s-list"),
            any());
  }

  /**
   * 验证 JobRuntimeServiceImpl 中岗位推荐的输入校验与拒绝边界。
   */
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
    when(runtimeToolClient.invoke(
            any(String.class), any(RuntimeToolArguments.class), any(String.class), any()))
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
    verify(runtimeToolClient)
        .invoke(
            eq("resume_match"),
            argThat(args -> "recommendation_list".equals(args.get("evaluation_mode").asText())),
            eq("s1"),
            any());
  }

  /**
   * 验证 JobRuntimeServiceImpl 中岗位的输入校验与拒绝边界。
   */
  @Test
  void jobRecommendationResultShouldRejectUnaccountedOrNegativeRejections() {
    IllegalArgumentException unaccounted =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new com.jobbuddy.backend.modules.chat.service.JobRecommendationResult(
                    Collections.singletonList(realJob("accepted")),
                    2,
                    Collections.<String, Integer>emptyMap(),
                    Collections.<String>emptyList()));
    assertTrue(unaccounted.getMessage().contains("漏斗计数不守恒"));

    IllegalArgumentException negative =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new com.jobbuddy.backend.modules.chat.service.JobRecommendationResult(
                    Collections.<Map<String, Object>>emptyList(),
                    0,
                    Collections.singletonMap("未达到最低匹配分", Integer.valueOf(-1)),
                    Collections.<String>emptyList()));
    assertTrue(negative.getMessage().contains("拒绝原因计数"));
  }

  /**
   * 验证 JobRuntimeServiceImpl 中岗位推荐的检索、筛选与排序规则。
   */
  @Test
  @SuppressWarnings("unchecked")
  void prequalifyRecommendationsShouldScoreAllTwentyThreeCandidatesAndConserveFunnel() {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setMaxJobsPerRecommend(30);
    properties.setMaxJobsPerScoring(23);
    List<Integer> scoredBatchSizes = Collections.synchronizedList(new ArrayList<Integer>());
    when(runtimeToolClient.invoke(
            any(String.class), any(RuntimeToolArguments.class), any(String.class), any()))
        .thenAnswer(
            invocation -> {
              RuntimeToolArguments toolArguments = invocation.getArgument(1);
              Map<String, Object> args = toolArguments.toMap(JSON);
              List<Map<String, Object>> jobs = (List<Map<String, Object>>) args.get("jobs");
              scoredBatchSizes.add(Integer.valueOf(jobs.size()));
              List<Map<String, Object>> matches = new ArrayList<Map<String, Object>>();
              for (Map<String, Object> job : jobs) {
                String id = String.valueOf(job.get("securityId"));
                int index = Integer.parseInt(id.substring("job-".length()));
                if (index == 3 || index == 19) {
                  matches.add(recommendationMatch(id, 82, "high", "推荐"));
                } else if (index % 3 == 0) {
                  matches.add(recommendationMatch(id, 50, "medium", "可尝试"));
                } else if (index % 3 == 1) {
                  matches.add(recommendationMatch(id, 82, "low", "推荐"));
                } else {
                  matches.add(recommendationMatch(id, 82, "high", "不建议"));
                }
              }
              return runtimeMatch(matches.toArray(new Map[0]));
            });
    JobRuntimeServiceImpl service =
        new JobRuntimeServiceImpl(
            runtimeToolClient,
            properties,
            bossAuthService,
            new JsonCodec(),
            bossCliService,
            settingsService);
    List<Map<String, Object>> candidates = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < 23; i++) candidates.add(realJob("job-" + i));

    com.jobbuddy.backend.modules.chat.service.JobRecommendationResult result =
        service.prequalifyRecommendations(parsedResume(), candidates, "s1");

    List<Integer> sortedBatchSizes = new ArrayList<Integer>(scoredBatchSizes);
    Collections.sort(sortedBatchSizes);
    assertEquals(java.util.Arrays.asList(3, 4, 4, 4, 4, 4), sortedBatchSizes);
    assertEquals(23, result.getCandidateCount());
    assertEquals(2, result.getQualifiedCount());
    assertEquals(21, result.getRejectedCount());
    assertEquals(
        java.util.Arrays.asList("job-3", "job-19"),
        result.getJobs().stream().map(row -> String.valueOf(row.get("securityId"))).toList());
    assertTrue(result.getRejectionReasons().containsKey("未达到最低匹配分"));
    assertTrue(result.getRejectionReasons().containsKey("匹配置信度低"));
    assertTrue(result.getRejectionReasons().containsKey("投递建议为不建议"));
    assertEquals(
        result.getCandidateCount(), result.getQualifiedCount() + result.getRejectedCount());
    verify(runtimeToolClient, times(6))
        .invoke(any(String.class), any(RuntimeToolArguments.class), any(String.class), any());
  }

  /**
   * 验证岗位候选达到两个批次时，互不依赖的 Runtime 简历匹配请求会有界并发执行。
   */
  @Test
  @SuppressWarnings("unchecked")
  void prequalifyRecommendationsShouldScoreIndependentBatchesInParallel() {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setMaxJobsPerRecommend(5);
    properties.setMaxJobsPerScoring(8);
    CountDownLatch concurrentStarts = new CountDownLatch(2);
    AtomicInteger active = new AtomicInteger();
    AtomicInteger maxActive = new AtomicInteger();
    when(runtimeToolClient.invoke(
            any(String.class), any(RuntimeToolArguments.class), any(String.class), any()))
        .thenAnswer(
            invocation -> {
              assertEquals("tenant-a", AuthenticationScope.tenantId());
              assertEquals("user-a", AuthenticationScope.userId());
              int current = active.incrementAndGet();
              maxActive.accumulateAndGet(current, Math::max);
              concurrentStarts.countDown();
              try {
                concurrentStarts.await(1, TimeUnit.SECONDS);
                RuntimeToolArguments toolArguments = invocation.getArgument(1);
                List<Map<String, Object>> jobs =
                    (List<Map<String, Object>>) toolArguments.toMap(JSON).get("jobs");
                List<Map<String, Object>> matches = new ArrayList<Map<String, Object>>();
                for (Map<String, Object> job : jobs) {
                  String id = String.valueOf(job.get("securityId"));
                  matches.add(recommendationMatch(id, 82, "high", "推荐"));
                }
                return runtimeMatch(matches.toArray(new Map[0]));
              } finally {
                active.decrementAndGet();
              }
            });
    JobRuntimeServiceImpl service =
        new JobRuntimeServiceImpl(
            runtimeToolClient,
            properties,
            bossAuthService,
            new JsonCodec(),
            bossCliService,
            settingsService);
    List<Map<String, Object>> candidates = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < 8; i++) candidates.add(realJob("parallel-" + i));

    com.jobbuddy.backend.modules.chat.service.JobRecommendationResult result =
        service.prequalifyRecommendations(parsedResume(), candidates, "s1");

    assertEquals(5, result.getQualifiedCount());
    assertEquals(3, result.getRejectedCount());
    assertEquals(3, result.getRejectionReasons().get("超出本轮展示上限"));
    assertTrue(maxActive.get() >= 2);
    assertEquals("tenant-a", AuthenticationScope.tenantId());
    assertEquals("user-a", AuthenticationScope.userId());
    verify(runtimeToolClient, times(2))
        .invoke(any(String.class), any(RuntimeToolArguments.class), any(String.class), any());
  }

  /**
   * 验证 JobRuntimeServiceImpl 中岗位的检索、筛选与排序规则。
   */
  @Test
  @SuppressWarnings("unchecked")
  void prequalifyRecommendationsShouldNotConsumeQualifiedJobsPastFinalDisplaySlot() {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setMaxJobsPerRecommend(3);
    properties.setMaxJobsPerScoring(6);
    List<List<String>> scoredIds = new ArrayList<List<String>>();
    when(runtimeToolClient.invoke(
            any(String.class), any(RuntimeToolArguments.class), any(String.class), any()))
        .thenAnswer(
            invocation -> {
              RuntimeToolArguments toolArguments = invocation.getArgument(1);
              Map<String, Object> args = toolArguments.toMap(JSON);
              List<Map<String, Object>> jobs = (List<Map<String, Object>>) args.get("jobs");
              List<String> batchIds = new ArrayList<String>();
              List<Map<String, Object>> matches = new ArrayList<Map<String, Object>>();
              for (Map<String, Object> job : jobs) {
                String id = String.valueOf(job.get("securityId"));
                batchIds.add(id);
                boolean accepted =
                    "job-0".equals(id)
                        || "job-3".equals(id)
                        || "job-4".equals(id)
                        || "job-5".equals(id);
                matches.add(
                    recommendationMatch(id, accepted ? 82 : 50, "medium", accepted ? "推荐" : "可尝试"));
              }
              scoredIds.add(batchIds);
              return runtimeMatch(matches.toArray(new Map[0]));
            });
    JobRuntimeServiceImpl service =
        new JobRuntimeServiceImpl(
            runtimeToolClient,
            properties,
            bossAuthService,
            new JsonCodec(),
            bossCliService,
            settingsService);
    List<Map<String, Object>> candidates = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < 6; i++) candidates.add(realJob("job-" + i));

    com.jobbuddy.backend.modules.chat.service.JobRecommendationResult result =
        service.prequalifyRecommendations(parsedResume(), candidates, "s1");

    assertEquals(
        java.util.Arrays.asList(
            java.util.Arrays.asList("job-0", "job-1", "job-2"),
            java.util.Arrays.asList("job-3", "job-4")),
        scoredIds);
    assertEquals(5, result.getCandidateCount());
    assertEquals(3, result.getQualifiedCount());
    assertEquals(2, result.getRejectedCount());
    assertEquals(
        java.util.Arrays.asList("job-0", "job-3", "job-4"),
        result.getJobs().stream().map(row -> String.valueOf(row.get("securityId"))).toList());
    assertEquals(
        result.getCandidateCount(), result.getQualifiedCount() + result.getRejectedCount());
  }

  /**
   * 验证 JobRuntimeServiceImpl 中岗位推荐的输入校验与拒绝边界。
   */
  @Test
  void prequalifyRecommendationsShouldRetryIncompleteBatchWithoutCountingMissingRowsAsLowScore() {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setMaxJobsPerRecommend(5);
    properties.setMaxJobsPerScoring(5);
    when(runtimeToolClient.invoke(
            any(String.class), any(RuntimeToolArguments.class), any(String.class), any()))
        .thenReturn(
            runtimeMatch(recommendationMatch("job-0", 82, "medium", "推荐")),
            runtimeMatch(
                recommendationMatch("job-0", 82, "medium", "推荐"),
                recommendationMatch("job-1", 78, "medium", "可尝试"),
                recommendationMatch("job-2", 76, "medium", "可尝试"),
                recommendationMatch("job-3", 74, "medium", "可尝试")),
            runtimeMatch(recommendationMatch("job-4", 72, "medium", "可尝试")));
    JobRuntimeServiceImpl service =
        new JobRuntimeServiceImpl(
            runtimeToolClient,
            properties,
            bossAuthService,
            new JsonCodec(),
            bossCliService,
            settingsService);
    List<Map<String, Object>> candidates = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < 5; i++) candidates.add(realJob("job-" + i));

    com.jobbuddy.backend.modules.chat.service.JobRecommendationResult result =
        service.prequalifyRecommendations(parsedResume(), candidates, "s1");

    assertEquals(5, result.getCandidateCount());
    assertEquals(5, result.getQualifiedCount());
    assertFalse(result.getRejectionReasons().containsKey("未达到最低匹配分"));
    verify(runtimeToolClient, times(3))
        .invoke(any(String.class), any(RuntimeToolArguments.class), any(String.class), any());
  }

  /**
   * 验证 JobRuntimeServiceImpl 中岗位推荐的失败恢复、超时与降级边界。
   */
  @Test
  void prequalifyRecommendationsShouldFailWhenSplitRetryIsStillIncomplete() {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setMaxJobsPerRecommend(2);
    properties.setMaxJobsPerScoring(2);
    when(runtimeToolClient.invoke(
            any(String.class), any(RuntimeToolArguments.class), any(String.class), any()))
        .thenReturn(
            runtimeMatch(recommendationMatch("job-0", 82, "medium", "推荐")),
            runtimeMatch(recommendationMatch("job-0", 82, "medium", "推荐")));
    JobRuntimeServiceImpl service =
        new JobRuntimeServiceImpl(
            runtimeToolClient,
            properties,
            bossAuthService,
            new JsonCodec(),
            bossCliService,
            settingsService);

    RuntimeException error =
        assertThrows(
            RuntimeException.class,
            () ->
                service.prequalifyRecommendations(
                    parsedResume(),
                    java.util.Arrays.asList(realJob("job-0"), realJob("job-1")),
                    "s1"));

    assertTrue(error.getMessage().contains("拆分重试"));
    verify(runtimeToolClient, times(2))
        .invoke(any(String.class), any(RuntimeToolArguments.class), any(String.class), any());
  }

  /**
   * 最终排序与截断完成后，只为即将下发的合格岗位补充职位描述，不触碰候选池中的淘汰岗位。
   */
  @Test
  @SuppressWarnings("unchecked")
  void prequalifyRecommendationsWithContinuationShouldEnrichOnlyFinalQualifiedJobs() {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setMaxJobsPerRecommend(2);
    properties.setMaxJobsPerScoring(4);
    when(runtimeToolClient.invoke(
            any(String.class), any(RuntimeToolArguments.class), any(String.class), any()))
        .thenAnswer(
            invocation -> {
              RuntimeToolArguments toolArguments = invocation.getArgument(1);
              List<Map<String, Object>> jobs =
                  (List<Map<String, Object>>) toolArguments.toMap(JSON).get("jobs");
              List<Map<String, Object>> matches = new ArrayList<Map<String, Object>>();
              for (Map<String, Object> job : jobs) {
                String id = String.valueOf(job.get("securityId"));
                int score = "job-0".equals(id) ? 70 : "job-2".equals(id) ? 90 : 50;
                matches.add(recommendationMatch(id, score, "high", score >= 60 ? "推荐" : "不建议"));
              }
              return runtimeMatch(matches.toArray(new Map[0]));
            });
    when(bossCliService.enrichJobDetails(any(List.class), anyInt()))
        .thenAnswer(
            invocation -> {
              List<Map<String, Object>> jobs = invocation.getArgument(0);
              List<Map<String, Object>> enriched = new ArrayList<Map<String, Object>>();
              for (Map<String, Object> job : jobs) {
                Map<String, Object> copy = new LinkedHashMap<String, Object>(job);
                copy.put("jobDescription", "最终合格岗位 " + job.get("securityId") + " 的完整职位描述。");
                enriched.add(copy);
              }
              return enriched;
            });
    JobRuntimeServiceImpl service =
        new JobRuntimeServiceImpl(
            runtimeToolClient,
            properties,
            bossAuthService,
            new JsonCodec(),
            bossCliService,
            settingsService);
    List<Map<String, Object>> candidates =
        java.util.Arrays.asList(
            realJob("job-0"), realJob("job-1"), realJob("job-2"), realJob("job-3"));
    candidates.get(0).put("description", "负责大模型应用开发。");
    IntentResult intent =
        new IntentResult(
            "job",
            "job.recommend",
            0.9,
            Collections.<String>emptyList(),
            "low",
            false,
            "call_get_recommend_jobs",
            Collections.singletonMap("role", "云原生平台开发"));

    com.jobbuddy.backend.modules.chat.service.JobRecommendationResult result =
        service.prequalifyRecommendationsWithContinuation(parsedResume(), intent, candidates, "s1");

    assertEquals(
        java.util.Arrays.asList("job-2", "job-0"),
        result.getJobs().stream().map(job -> String.valueOf(job.get("securityId"))).toList());
    assertTrue(
        result.getJobs().stream()
            .allMatch(job -> String.valueOf(job.get("jobDescription")).contains("完整职位描述")));
    assertTrue(
        result.getJobs().stream()
            .allMatch(job -> "list_metadata".equals(job.get("recommendationEvidenceLevel"))));
    verify(bossCliService, times(1))
        .enrichJobDetails(
            argThat(
                jobs ->
                    jobs.size() == 2
                        && "job-2".equals(jobs.get(0).get("securityId"))
                        && "job-0".equals(jobs.get(1).get("securityId"))),
            eq(2));
  }

  /**
   * 验证 JobRuntimeServiceImpl 中岗位推荐的输入校验与拒绝边界。
   */
  @Test
  @SuppressWarnings("unchecked")
  void prequalifyRecommendationsShouldContinueBossSearchWhenInitialCandidatesAreRejected() {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setMaxJobsPerRecommend(1);
    properties.setRecommendOverfetchFactor(2);
    properties.setMaxJobsPerScoring(4);
    properties.setBossSearchMaxPages(1);
    properties.setBossSearchMaxPageDepth(2);
    properties.setBossSearchPageDelayMillis(0);
    when(bossCliService.searchJobsFirstPage(any(IntentResult.class)))
        .thenReturn(jobsWithPrefix("p1-", 2));
    when(bossCliService.searchJobsPage(any(IntentResult.class), anyInt()))
        .thenReturn(jobsWithPrefix("p2-", 2));
    when(settingsService.filterBlacklistedJobs(any(List.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(runtimeToolClient.invoke(
            any(String.class), any(RuntimeToolArguments.class), any(String.class), any()))
        .thenAnswer(
            invocation -> {
              RuntimeToolArguments toolArguments = invocation.getArgument(1);
              Map<String, Object> args = toolArguments.toMap(JSON);
              List<Map<String, Object>> scoredJobs = (List<Map<String, Object>>) args.get("jobs");
              String id = String.valueOf(scoredJobs.get(0).get("securityId"));
              int score = "p2-0".equals(id) ? 65 : 55;
              return runtimeMatch(
                  recommendationMatch(id, score, "medium", score >= 60 ? "推荐" : "可尝试"));
            });
    JobRuntimeServiceImpl service =
        new JobRuntimeServiceImpl(
            runtimeToolClient,
            properties,
            bossAuthService,
            new JsonCodec(),
            bossCliService,
            settingsService);
    Map<String, Object> slots = new LinkedHashMap<String, Object>();
    slots.put("role", "云原生平台开发");
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

    List<Map<String, Object>> initial = service.recommendJobsFast(intent, "s1", null);
    com.jobbuddy.backend.modules.chat.service.JobRecommendationResult result =
        service.prequalifyRecommendationsWithContinuation(parsedResume(), intent, initial, "s1");

    assertEquals(60, properties.getMinimumRecommendedMatchScore());
    assertEquals(3, result.getCandidateCount());
    assertEquals(1, result.getQualifiedCount());
    assertEquals(2, result.getRejectedCount());
    assertEquals("p2-0", result.getJobs().get(0).get("securityId"));
    verify(bossCliService, times(1)).searchJobsPage(any(IntentResult.class), anyInt());
    verify(runtimeToolClient, times(3))
        .invoke(any(String.class), any(RuntimeToolArguments.class), any(String.class), any());
  }

  /**
   * 验证 JobRuntimeServiceImpl 中岗位推荐的检索、筛选与排序规则。
   */
  @Test
  void prequalifyRecommendationsShouldContinueFromEmptyInitialBatchWithinBossPageDepth() {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setMaxJobsPerRecommend(1);
    properties.setRecommendOverfetchFactor(1);
    properties.setMaxJobsPerScoring(3);
    properties.setBossSearchMaxPages(1);
    properties.setBossSearchMaxPageDepth(2);
    properties.setBossSearchPageDelayMillis(0);
    when(bossCliService.searchJobsFirstPage(any(IntentResult.class)))
        .thenReturn(Collections.singletonList(job("p1-low", "云原生平台开发", "10-20K")));
    when(bossCliService.searchJobsPage(any(IntentResult.class), eq(2)))
        .thenReturn(Collections.singletonList(job("p2-fit", "云原生平台开发", "25-35K")));
    when(settingsService.filterBlacklistedJobs(any(List.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(runtimeToolClient.invoke(
            any(String.class), any(RuntimeToolArguments.class), any(String.class), any()))
        .thenReturn(runtimeMatch(recommendationMatch("p2-fit", 82, "high", "推荐")));
    JobRuntimeServiceImpl service =
        new JobRuntimeServiceImpl(
            runtimeToolClient,
            properties,
            bossAuthService,
            new JsonCodec(),
            bossCliService,
            settingsService);
    Map<String, Object> slots = new LinkedHashMap<String, Object>();
    slots.put("role", "云原生平台开发");
    slots.put("salary_min_k", 25);
    slots.put("salary_max_k", 35);
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

    com.jobbuddy.backend.modules.chat.service.JobRecommendationResult result =
        service.prequalifyRecommendationsWithContinuation(
            parsedResume(), intent, Collections.<Map<String, Object>>emptyList(), "s1");

    assertEquals(1, result.getCandidateCount());
    assertEquals(1, result.getQualifiedCount());
    assertEquals(0, result.getRejectedCount());
    assertEquals("p2-fit", result.getJobs().get(0).get("securityId"));
    assertFalse(String.valueOf(result.getWarnings()).contains("当前批次没有岗位"));
    verify(bossCliService).searchJobsFirstPage(any(IntentResult.class));
    verify(bossCliService).searchJobsPage(any(IntentResult.class), eq(2));
    verify(runtimeToolClient)
        .invoke(any(String.class), any(RuntimeToolArguments.class), any(String.class), any());
  }

  /**
   * 验证 JobRuntimeServiceImpl 中岗位推荐的检索、筛选与排序规则。
   */
  @Test
  void prequalifyRecommendationsShouldStopEmptyContinuationAndReturnNoQualifiedWarning() {
    RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
    BossAuthService bossAuthService = mock(BossAuthService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setMaxJobsPerRecommend(1);
    properties.setMaxJobsPerScoring(3);
    properties.setBossSearchMaxPages(1);
    properties.setBossSearchMaxPageDepth(2);
    properties.setBossSearchPageDelayMillis(0);
    when(bossCliService.searchJobsFirstPage(any(IntentResult.class)))
        .thenReturn(Collections.<Map<String, Object>>emptyList());
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
    IntentResult intent =
        new IntentResult(
            "job",
            "job.recommend",
            0.9,
            Collections.<String>emptyList(),
            "low",
            false,
            "call_get_recommend_jobs",
            Collections.singletonMap("role", "云原生平台开发"));

    com.jobbuddy.backend.modules.chat.service.JobRecommendationResult result =
        service.prequalifyRecommendationsWithContinuation(
            parsedResume(), intent, Collections.<Map<String, Object>>emptyList(), "s1");

    assertEquals(0, result.getCandidateCount());
    assertEquals(0, result.getQualifiedCount());
    assertEquals(0, result.getRejectedCount());
    assertTrue(String.valueOf(result.getWarnings()).contains("当前批次没有岗位"));
    verify(bossCliService).searchJobsFirstPage(any(IntentResult.class));
    verify(bossCliService, never()).searchJobsPage(any(IntentResult.class), anyInt());
    verify(runtimeToolClient, never())
        .invoke(any(String.class), any(RuntimeToolArguments.class), any(String.class), any());
  }

  /**
   * 验证 JobRuntimeServiceImpl 中简历的输入校验与拒绝边界。
   */
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
    parsed.put("skills", Collections.singletonList("Go"));
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
        .invoke(
            any(String.class),
            any(RuntimeToolArguments.class),
            any(String.class),
            any(String.class));
  }

  /**
   * 构造已解析简历数据。
   *
   * @return 解析结果
   */
  private ResumeRecord parsedResume() {
    ResumeRecord resume = new ResumeRecord();
    Map<String, Object> parsed = new LinkedHashMap<String, Object>();
    parsed.put("skills", Collections.singletonList("Java"));
    resume.setParsed(parsed);
    return resume;
  }

  /**
   * 构造真实岗位测试数据。
   *
   * @param id 标识
   * @return 真实岗位测试数据
   */
  private Map<String, Object> realJob(String id) {
    Map<String, Object> job = new LinkedHashMap<String, Object>();
    job.put("securityId", id);
    job.put("source", "boss");
    job.put("jobName", "杭州 Go 云原生平台开发岗");
    job.put("salaryDesc", "25-35K");
    return job;
  }

  /**
   * 验证匹配记录。
   *
   * @param id 标识
   * @param score 评分
   * @return 匹配记录
   */
  private Map<String, Object> matchRow(String id, int score) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("id", id);
    row.put("score", score);
    row.put("evidence", Collections.singletonList("岗位要求与简历技术栈一致"));
    return row;
  }

  /**
   * 验证推荐匹配。
   *
   * @param id 标识
   * @param score 评分
   * @param confidence 置信度
   * @param recommendation 推荐
   * @return recommendation 匹配
   */
  private Map<String, Object> recommendationMatch(
      String id, int score, String confidence, String recommendation) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("id", id);
    row.put("score", score);
    row.put("score_confidence", confidence);
    row.put("recommendation", recommendation);
    row.put("hits", Collections.singletonList("Go、Kubernetes 与平台工程能力匹配岗位要求"));
    return row;
  }

  /**
   * 验证运行时匹配。
   *
   * @param matches 匹配结果列表
   * @return runtime 匹配
   */
  private RuntimeToolResult runtimeMatch(Map<String, Object>... matches) {
    Map<String, Object> output = new LinkedHashMap<String, Object>();
    output.put("matches", java.util.Arrays.asList(matches));
    output.put("scored_count", matches.length);
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("success", true);
    result.put("output", output);
    return RuntimeToolResult.fromJson(JSON.toTree(result));
  }

  /**
   * 验证岗位列表。
   *
   * @param count 数量
   * @return 测试岗位 s
   */
  private List<Map<String, Object>> jobs(int count) {
    List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < count; i++) {
      Map<String, Object> row = new LinkedHashMap<String, Object>();
      row.put("securityId", "sid" + i);
      row.put("jobName", "Go 云原生平台开发工程师 " + i);
      row.put("salaryDesc", "25-35K");
      rows.add(row);
    }
    return rows;
  }

  /**
   * 构造带统一前缀的岗位列表。
   *
   * @param prefix 名称前缀
   * @param count 数量
   * @return 测试岗位 sWithPrefix
   */
  private List<Map<String, Object>> jobsWithPrefix(String prefix, int count) {
    List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < count; i++) {
      rows.add(job(prefix + i, "云原生平台开发 " + prefix + i, "25-35K"));
    }
    return rows;
  }

  /**
   * 验证岗位。
   *
   * @param securityId 安全标识
   * @param jobName 岗位名称
   * @param salaryDesc 薪资说明
   * @return 测试岗位
   */
  private Map<String, Object> job(String securityId, String jobName, String salaryDesc) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("securityId", securityId);
    row.put("jobName", jobName);
    row.put("salaryDesc", salaryDesc);
    return row;
  }

  /**
   * 构造带薪资上下限的岗位。
   *
   * @param securityId 安全标识
   * @param jobName 岗位名称
   * @param lowSalary 最低薪资
   * @param highSalary 最高薪资
   * @return 测试岗位 WithSalaryBounds
   */
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
