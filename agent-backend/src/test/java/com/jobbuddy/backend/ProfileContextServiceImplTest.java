package com.jobbuddy.backend;

import com.jobbuddy.backend.modules.prompt.model.UserProfileContext;
import com.jobbuddy.backend.modules.prompt.service.impl.ProfileContextServiceImpl;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProfileContextServiceImplTest {

    @Test
    void shouldMergeParsedProfileAndSummarizeAliasFields() {
        ResumeStorageService storage = mock(ResumeStorageService.class);
        Map<String, Object> parsed = new LinkedHashMap<String, Object>();
        parsed.put("name", "张三");
        parsed.put("currentTitle", "Java 后端工程师");
        parsed.put("skills", Arrays.asList("Java", "Spring"));
        Map<String, Object> expectation = new LinkedHashMap<String, Object>();
        expectation.put("city", "上海");
        expectation.put("salary_range", "25k-35k");
        parsed.put("job_expectations", expectation);
        when(storage.getJobProfileOrEmpty("u1")).thenReturn(envelope(parsed));

        UserProfileContext context = new ProfileContextServiceImpl(storage).current("u1", null);

        assertEquals("张三", context.getProfile().get("name"));
        String summary = context.getSummary();
        assertTrue(summary.contains("姓名：张三"));
        assertTrue(summary.contains("当前方向：Java 后端工程师"));
        assertTrue(summary.contains("城市：上海"));
        assertTrue(summary.contains("薪资：25k-35k"));
    }

    @Test
    void expectationListShouldUseFirstEntry() {
        ResumeStorageService storage = mock(ResumeStorageService.class);
        Map<String, Object> parsed = new LinkedHashMap<String, Object>();
        Map<String, Object> intention = new LinkedHashMap<String, Object>();
        intention.put("positionName", "后端开发");
        parsed.put("jobIntentions", Arrays.asList(intention));
        when(storage.getJobProfileOrEmpty("u1")).thenReturn(envelope(parsed));

        UserProfileContext context = new ProfileContextServiceImpl(storage).current("u1", null);

        assertTrue(context.getSummary().contains("岗位：后端开发"));
    }

    @Test
    void profileReadFailureShouldDegradeToEmptyProfile() {
        ResumeStorageService storage = mock(ResumeStorageService.class);
        when(storage.getJobProfileOrEmpty("u1")).thenThrow(new IllegalStateException("db down"));

        UserProfileContext context = new ProfileContextServiceImpl(storage).current("u1", null);

        assertTrue(context.getProfile().isEmpty());
        assertEquals("", context.getSummary());
    }

    @Test
    void resumeReadFailureShouldDegradeWithoutResume() {
        ResumeStorageService storage = mock(ResumeStorageService.class);
        Map<String, Object> parsed = new LinkedHashMap<String, Object>();
        parsed.put("name", "李四");
        when(storage.getJobProfileOrEmpty("u1")).thenReturn(envelope(parsed));
        when(storage.get("r1", "u1")).thenThrow(new IllegalStateException("storage error"));

        UserProfileContext context = new ProfileContextServiceImpl(storage).current("u1", "r1");

        assertEquals("李四", context.getProfile().get("name"));
        assertFalse(context.getProfile().containsKey("current_resume"));
    }

    @Test
    void currentResumeShouldBeAttachedWhenPresent() {
        ResumeStorageService storage = mock(ResumeStorageService.class);
        when(storage.getJobProfileOrEmpty("u1")).thenReturn(envelope(new LinkedHashMap<String, Object>()));
        ResumeRecord record = new ResumeRecord();
        Map<String, Object> resumeParsed = new LinkedHashMap<String, Object>();
        resumeParsed.put("projects", Arrays.asList("job-buddy"));
        record.setParsed(resumeParsed);
        when(storage.get("r1", "u1")).thenReturn(record);

        UserProfileContext context = new ProfileContextServiceImpl(storage).current("u1", "r1");

        assertEquals(resumeParsed, context.getProfile().get("current_resume"));
    }

    @Test
    void longSummaryFieldShouldBeTruncatedTo180Chars() {
        ResumeStorageService storage = mock(ResumeStorageService.class);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 300; i++) text.append("长");
        Map<String, Object> parsed = new LinkedHashMap<String, Object>();
        parsed.put("summary", text.toString());
        when(storage.getJobProfileOrEmpty("u1")).thenReturn(envelope(parsed));

        String summary = new ProfileContextServiceImpl(storage).current("u1", null).getSummary();

        assertTrue(summary.contains("摘要："));
        assertTrue(summary.endsWith("..."));
        assertEquals("摘要：".length() + 180 + "...".length(), summary.length());
    }

    private Map<String, Object> envelope(Map<String, Object> parsed) {
        Map<String, Object> profile = new LinkedHashMap<String, Object>();
        profile.put("parsed", parsed);
        return profile;
    }
}
