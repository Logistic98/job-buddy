package com.jobbuddy.backend;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.auth.service.BossAuthService;
import com.jobbuddy.backend.modules.auth.service.BossCliService;
import com.jobbuddy.backend.modules.chat.service.RuntimeToolClient;
import com.jobbuddy.backend.modules.chat.service.impl.JobRuntimeServiceImpl;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.system.service.SystemSettingsService;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class JobRuntimeServiceImplTest {

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
        properties.setBossLiveEnabled(true);
        JsonCodec jsonCodec = new JsonCodec();

        // 真实 Boss 单页返回的岗位数远多于单屏 limit，首屏只抓 1 页即可覆盖首屏切片与下一批换一批切片。
        when(bossCliService.searchJobsFirstPage(any(IntentResult.class), any(BossCliService.JobBatchConsumer.class))).thenReturn(jobsWithPrefix("p1-", 4));
        when(settingsService.filterBlacklistedJobs(any(List.class))).thenAnswer(invocation -> invocation.getArgument(0));

        JobRuntimeServiceImpl service = new JobRuntimeServiceImpl(runtimeToolClient, properties, bossAuthService, jsonCodec, bossCliService, settingsService);

        Map<String, Object> firstSlots = new LinkedHashMap<String, Object>();
        firstSlots.put("role", "大模型应用开发");
        IntentResult firstIntent = new IntentResult("job", "job.recommend", 0.9, Collections.<String>emptyList(), "low", false, "call_get_recommend_jobs", firstSlots);
        List<Map<String, Object>> firstResult = service.recommendJobsFast(firstIntent, "s1", null);

        // 换一批：同一组检索条件、boss_page=2，命中同一份候选池缓存切片，不再请求 Boss。
        Map<String, Object> flipSlots = new LinkedHashMap<String, Object>();
        flipSlots.put("role", "大模型应用开发");
        flipSlots.put("boss_page", 2);
        IntentResult flipIntent = new IntentResult("job", "job.recommend", 0.9, Collections.<String>emptyList(), "low", false, "call_get_recommend_jobs", flipSlots);
        List<Map<String, Object>> flipResult = service.recommendJobsFast(flipIntent, "s1", null);

        assertEquals(2, firstResult.size());
        assertEquals(2, flipResult.size());
        // 两批切片不重叠，确认换一批刷出的是候选池里的下一批岗位。
        assertNotEquals(firstResult.get(0).get("securityId"), flipResult.get(0).get("securityId"));
        // 首屏只抓 1 页（单次 Boss 请求即出结果）；换一批从同页候选池缓存切片，零额外 Boss 请求。
        verify(bossCliService, times(1)).searchJobsFirstPage(any(IntentResult.class), any(BossCliService.JobBatchConsumer.class));
        verify(bossCliService, never()).searchJobsPage(any(IntentResult.class), anyInt());
        verify(bossCliService, never()).enrichJobDetails(any(List.class), anyInt());
        // 仅首屏真实抓取记忆一次凭证；换一批命中缓存不触发登录态副作用。
        verify(bossAuthService, times(1)).rememberCurrentCredential(any(Map.class));
    }

    @Test
    void recommendJobsFastShouldDropOffSalaryAndInternJobsWhenSalaryRangeGiven() {
        RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
        BossAuthService bossAuthService = mock(BossAuthService.class);
        BossCliService bossCliService = mock(BossCliService.class);
        SystemSettingsService settingsService = mock(SystemSettingsService.class);
        JobBuddyProperties properties = new JobBuddyProperties();
        properties.setMaxJobsPerRecommend(3);
        properties.setBossSearchMaxPages(1);
        properties.setBossSearchPageDelayMillis(0);

        List<Map<String, Object>> source = new ArrayList<Map<String, Object>>();
        source.add(job("ok", "大模型应用开发工程师", "40-50K"));
        source.add(job("overlap", "大模型平台开发", "45-60K"));
        source.add(job("low", "Java 开发", "17-18K"));
        source.add(job("dayIntern", "大模型实习生", "490-500元/天"));
        source.add(job("day", "数据标注", "200-300元/天"));
        source.add(job("negotiable", "大模型应用开发", "面议"));

        when(bossCliService.searchJobsFirstPage(any(IntentResult.class), any(BossCliService.JobBatchConsumer.class))).thenReturn(source);
        when(settingsService.filterBlacklistedJobs(any(List.class))).thenAnswer(invocation -> invocation.getArgument(0));

        JobRuntimeServiceImpl service = new JobRuntimeServiceImpl(runtimeToolClient, properties, bossAuthService, new JsonCodec(), bossCliService, settingsService);
        Map<String, Object> slots = new LinkedHashMap<String, Object>();
        slots.put("role", "大模型应用开发");
        slots.put("salary_min_k", 40);
        slots.put("salary_max_k", 50);
        IntentResult intent = new IntentResult("job", "job.recommend", 0.9, Collections.<String>emptyList(), "low", false, "call_get_recommend_jobs", slots);

        List<Map<String, Object>> result = service.recommendJobsFast(intent, "s1", null);

        java.util.Set<Object> keptIds = new java.util.HashSet<Object>();
        for (Map<String, Object> row : result) keptIds.add(row.get("securityId"));
        assertEquals(3, result.size());
        assertTrue(keptIds.contains("ok"));
        assertTrue(keptIds.contains("overlap"));
        assertTrue(keptIds.contains("negotiable"));
        assertTrue(!keptIds.contains("low"));
        assertTrue(!keptIds.contains("day"));
        assertTrue(!keptIds.contains("dayIntern"));
    }

    @Test
    void recommendJobsFastShouldUseRealSearchEvenWhenLegacyBossLiveFlagDisabled() {
        RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
        BossAuthService bossAuthService = mock(BossAuthService.class);
        BossCliService bossCliService = mock(BossCliService.class);
        SystemSettingsService settingsService = mock(SystemSettingsService.class);
        JobBuddyProperties properties = new JobBuddyProperties();
        properties.setMaxJobsPerRecommend(2);
        properties.setBossLiveEnabled(false);
        List<Map<String, Object>> sourceJobs = jobs(2);
        when(bossCliService.searchJobsFirstPage(any(IntentResult.class), any(BossCliService.JobBatchConsumer.class))).thenReturn(sourceJobs);
        when(settingsService.filterBlacklistedJobs(any(List.class))).thenAnswer(invocation -> invocation.getArgument(0));

        JobRuntimeServiceImpl service = new JobRuntimeServiceImpl(runtimeToolClient, properties, bossAuthService, new JsonCodec(), bossCliService, settingsService);
        Map<String, Object> slots = new LinkedHashMap<String, Object>();
        slots.put("role", "大模型应用开发");
        IntentResult intent = new IntentResult("job", "job.recommend", 0.9, Collections.<String>emptyList(), "low", false, "call_get_recommend_jobs", slots);

        List<Map<String, Object>> result = service.recommendJobsFast(intent, "s1", null);

        assertEquals(2, result.size());
        verify(bossCliService).searchJobsFirstPage(any(IntentResult.class), any(BossCliService.JobBatchConsumer.class));
    }

    @Test
    void bossCandidatePoolTimeoutShouldAllowSlowFirstPageSearchByDefault() {
        RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
        BossAuthService bossAuthService = mock(BossAuthService.class);
        BossCliService bossCliService = mock(BossCliService.class);
        SystemSettingsService settingsService = mock(SystemSettingsService.class);
        JobRuntimeServiceImpl service = new JobRuntimeServiceImpl(runtimeToolClient, new JobBuddyProperties(), bossAuthService, new JsonCodec(), bossCliService, settingsService);

        assertTrue(service.bossCandidatePoolTimeoutSeconds() >= 30);
    }

    @Test
    void matchResumeShouldRejectFixtureEvidenceBeforeCallingRuntimeTool() {
        RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
        BossAuthService bossAuthService = mock(BossAuthService.class);
        BossCliService bossCliService = mock(BossCliService.class);
        SystemSettingsService settingsService = mock(SystemSettingsService.class);
        JobRuntimeServiceImpl service = new JobRuntimeServiceImpl(runtimeToolClient, new JobBuddyProperties(), bossAuthService, new JsonCodec(), bossCliService, settingsService);
        ResumeRecord resume = new ResumeRecord();
        Map<String, Object> parsed = new LinkedHashMap<String, Object>();
        parsed.put("skills", Collections.singletonList("Java"));
        resume.setParsed(parsed);
        Map<String, Object> job = new LinkedHashMap<String, Object>();
        job.put("source", "fixture");
        job.put("jobName", "Java 工程师");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.matchResume(resume, Collections.singletonList(job), "s1"));

        assertTrue(error.getMessage().contains("来源无效"));
        verify(runtimeToolClient, never()).invoke(any(String.class), any(Map.class), any(String.class), any(String.class));
    }

    private List<Map<String, Object>> jobs(int count) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("securityId", "sid" + i);
            row.put("jobName", "Java 工程师 " + i);
            row.put("salaryDesc", "20-30K");
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
}
