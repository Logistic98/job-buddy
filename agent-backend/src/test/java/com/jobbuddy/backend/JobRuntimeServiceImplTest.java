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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobRuntimeServiceImplTest {

    @Test
    void recommendJobsFastShouldUseSinglePageCandidatePoolAndSkipDetailEnrichmentByDefault() {
        RuntimeToolClient runtimeToolClient = mock(RuntimeToolClient.class);
        BossAuthService bossAuthService = mock(BossAuthService.class);
        BossCliService bossCliService = mock(BossCliService.class);
        SystemSettingsService settingsService = mock(SystemSettingsService.class);
        JobBuddyProperties properties = new JobBuddyProperties();
        properties.setMaxJobsPerRecommend(2);
        properties.setMaxJobsPerScoring(80);
        properties.setBossSearchPageDelayMillis(0);
        properties.setBossLiveEnabled(true);
        JsonCodec jsonCodec = new JsonCodec();
        List<Map<String, Object>> sourceJobs = jobs(3);

        when(bossCliService.searchJobsFirstPage(any(IntentResult.class), any(BossCliService.JobBatchConsumer.class))).thenReturn(sourceJobs);
        when(settingsService.filterBlacklistedJobs(any(List.class))).thenAnswer(invocation -> invocation.getArgument(0));

        JobRuntimeServiceImpl service = new JobRuntimeServiceImpl(runtimeToolClient, properties, bossAuthService, jsonCodec, bossCliService, settingsService);
        IntentResult intent = new IntentResult("job", "job.recommend", 0.9, Collections.<String>emptyList(), "low", false, "call_get_recommend_jobs");

        List<Map<String, Object>> result = service.recommendJobsFast(intent, "s1", null);

        assertEquals(2, result.size());
        verify(bossCliService).searchJobsFirstPage(any(IntentResult.class), any(BossCliService.JobBatchConsumer.class));
        verify(bossCliService, never()).searchJobsPage(any(IntentResult.class), eq(2));
        verify(bossCliService, never()).enrichJobDetails(any(List.class), anyInt());
        verify(bossAuthService).rememberCurrentCredential(any(Map.class));
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
}
