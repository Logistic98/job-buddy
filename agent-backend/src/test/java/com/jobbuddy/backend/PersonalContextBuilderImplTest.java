package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.security.AuthenticationScope;
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import com.jobbuddy.backend.modules.job.service.JobFavoriteService;
import com.jobbuddy.backend.modules.journey.service.JobJourneyService;
import com.jobbuddy.backend.modules.prompt.model.PersonalContext;
import com.jobbuddy.backend.modules.prompt.model.UserProfileContext;
import com.jobbuddy.backend.modules.prompt.service.ProfileContextService;
import com.jobbuddy.backend.modules.prompt.service.impl.PersonalContextBuilderImpl;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;
import com.jobbuddy.backend.modules.system.service.SystemSettingsService;
import java.util.Collections;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 验证个人上下文按任务相关性并行读取独立数据源，同时保持身份和结果边界。
 */
class PersonalContextBuilderImplTest {

  /**
   * 岗位推荐的确定性筛选只使用画像、当前简历与黑名单；长期记忆不参与槽位补全，不能拖慢首屏搜索。
   */
  @Test
  void jobRecommendationShouldSkipLongTermMemoryLookup() {
    ProfileContextService profileContextService = mock(ProfileContextService.class);
    ResumeStorageService resumeStorageService = mock(ResumeStorageService.class);
    JobFavoriteService favoriteService = mock(JobFavoriteService.class);
    JobJourneyService journeyService = mock(JobJourneyService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    when(profileContextService.current(anyString(), anyString()))
        .thenReturn(new UserProfileContext(Collections.singletonMap("role", "Agent"), "画像"));
    ResumeRecord record = new ResumeRecord();
    record.setParsed(Collections.singletonMap("skills", Collections.singletonList("Java")));
    when(resumeStorageService.get(anyString(), anyString())).thenReturn(record);
    when(settingsService.listBlacklistItems()).thenReturn(Collections.emptyList());

    PersonalContextBuilderImpl builder =
        new PersonalContextBuilderImpl(
            profileContextService,
            resumeStorageService,
            favoriteService,
            journeyService,
            settingsService);
    ChatSessionState state = new ChatSessionState();
    state.tenantId = "tenant-a";
    state.userId = "user-a";
    state.resumeId = "resume-a";
    IntentResult intent =
        new IntentResult(
            "job",
            "job.recommend",
            0.9,
            Collections.<String>emptyList(),
            "low",
            false,
            "call_get_recommend_jobs");
    try {
      PersonalContext context = builder.build("tenant-a", "user-a", "筛选上海 Java 岗位", intent, state);

      assertTrue(context.getLongTermMemory().isEmpty());
      verify(settingsService, never())
          .searchLocalMemories(anyString(), anyString(), anyString(), anyInt());
    } finally {
      builder.shutdown();
    }
  }

  /**
   * 画像、简历、收藏、旅程与长期记忆互不依赖，应同时开始读取，避免串行叠加 I/O 时延。
   *
   * @throws Exception 并发测试失败时抛出
   */
  @Test
  void buildShouldLoadIndependentContextSourcesConcurrently() throws Exception {
    ProfileContextService profileContextService = mock(ProfileContextService.class);
    ResumeStorageService resumeStorageService = mock(ResumeStorageService.class);
    JobFavoriteService favoriteService = mock(JobFavoriteService.class);
    JobJourneyService journeyService = mock(JobJourneyService.class);
    SystemSettingsService settingsService = mock(SystemSettingsService.class);
    CountDownLatch started = new CountDownLatch(5);
    CountDownLatch release = new CountDownLatch(1);
    ConcurrentLinkedQueue<String> observedOwners = new ConcurrentLinkedQueue<String>();

    when(profileContextService.current(anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              observedOwners.add(currentOwner());
              awaitRelease(started, release);
              return new UserProfileContext(Collections.singletonMap("role", "Agent"), "画像");
            });
    when(resumeStorageService.get(anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              observedOwners.add(currentOwner());
              awaitRelease(started, release);
              ResumeRecord record = new ResumeRecord();
              record.setParsed(
                  Collections.singletonMap("skills", Collections.singletonList("Java")));
              return record;
            });
    when(favoriteService.listFavorites(anyString()))
        .thenAnswer(
            invocation -> {
              observedOwners.add(currentOwner());
              awaitRelease(started, release);
              return Collections.emptyList();
            });
    when(journeyService.listRecords(anyString(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              observedOwners.add(currentOwner());
              awaitRelease(started, release);
              return Collections.emptyList();
            });
    when(settingsService.searchLocalMemories(anyString(), anyString(), anyString(), anyInt()))
        .thenAnswer(
            invocation -> {
              observedOwners.add(currentOwner());
              awaitRelease(started, release);
              return Collections.emptyList();
            });

    PersonalContextBuilderImpl builder =
        new PersonalContextBuilderImpl(
            profileContextService,
            resumeStorageService,
            favoriteService,
            journeyService,
            settingsService);
    ChatSessionState state = new ChatSessionState();
    state.tenantId = "tenant-a";
    state.userId = "user-a";
    state.resumeId = "resume-a";
    IntentResult intent =
        new IntentResult(
            "job",
            "interview.prepare",
            0.9,
            Collections.<String>emptyList(),
            "low",
            false,
            "run_runtime_planner");
    ExecutorService caller = Executors.newSingleThreadExecutor();
    try {
      Future<PersonalContext> result =
          caller.submit(() -> builder.build("tenant-a", "user-a", "准备面试问题", intent, state));
      boolean allStarted = started.await(1, TimeUnit.SECONDS);
      release.countDown();
      result.get(2, TimeUnit.SECONDS);

      assertTrue(allStarted, "独立个人上下文数据源应并行开始读取");
      assertEquals(5, observedOwners.size());
      assertTrue(
          observedOwners.stream().allMatch("tenant-a:user-a"::equals), "并行工作线程必须继承当前租户和用户身份");
    } finally {
      release.countDown();
      caller.shutdownNow();
      builder.shutdown();
    }
  }

  private static void awaitRelease(CountDownLatch started, CountDownLatch release)
      throws InterruptedException {
    started.countDown();
    release.await(2, TimeUnit.SECONDS);
  }

  private static String currentOwner() {
    return AuthenticationScope.tenantId() + ":" + AuthenticationScope.userId();
  }
}
