package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.prompt.model.UserProfileContext;
import com.jobbuddy.backend.modules.prompt.service.impl.ProfileContextServiceImpl;
import com.jobbuddy.backend.modules.resume.dto.response.ResumeSummaryResponse;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 验证 ProfileContextServiceImpl 的核心行为、异常路径与边界条件。
 */
class ProfileContextServiceImplTest {
  private static final JsonCodec JSON = new JsonCodec();

  /**
   * 验证 ProfileContextServiceImpl 中文件的文件解析与存储边界。
   */
  @Test
  void shouldMergeParsedProfileAndSummarizeAliasFields() {
    ResumeStorageService storage = mock(ResumeStorageService.class);
    Map<String, Object> parsed = new LinkedHashMap<String, Object>();
    parsed.put("name", "示例候选人");
    parsed.put("currentTitle", "Go 云原生平台开发工程师");
    parsed.put("skills", Arrays.asList("Go", "Kubernetes"));
    Map<String, Object> expectation = new LinkedHashMap<String, Object>();
    expectation.put("city", "杭州");
    expectation.put("salary_range", "25k-35k");
    parsed.put("job_expectations", expectation);
    when(storage.getJobProfileOrEmpty("u1")).thenReturn(envelope(parsed));

    UserProfileContext context = new ProfileContextServiceImpl(storage).current("u1", null);

    assertEquals("示例候选人", context.getProfile().get("name").asText());
    String summary = context.getSummary();
    assertTrue(summary.contains("姓名：示例候选人"));
    assertTrue(summary.contains("当前方向：Go 云原生平台开发工程师"));
    assertTrue(summary.contains("城市：杭州"));
    assertTrue(summary.contains("薪资：25k-35k"));
  }

  /**
   * 验证 ProfileContextServiceImpl 的核心业务契约。
   */
  @Test
  void expectationListShouldUseFirstEntry() {
    ResumeStorageService storage = mock(ResumeStorageService.class);
    Map<String, Object> parsed = new LinkedHashMap<String, Object>();
    Map<String, Object> intention = new LinkedHashMap<String, Object>();
    intention.put("positionName", "Go 云原生平台开发");
    parsed.put("jobIntentions", Arrays.asList(intention));
    when(storage.getJobProfileOrEmpty("u1")).thenReturn(envelope(parsed));

    UserProfileContext context = new ProfileContextServiceImpl(storage).current("u1", null);

    assertTrue(context.getSummary().contains("岗位：Go 云原生平台开发"));
  }

  /**
   * 验证 ProfileContextServiceImpl 中文件的失败恢复、超时与降级边界。
   */
  @Test
  void profileReadFailureShouldDegradeToEmptyProfile() {
    ResumeStorageService storage = mock(ResumeStorageService.class);
    when(storage.getJobProfileOrEmpty("u1")).thenThrow(new IllegalStateException("db down"));

    UserProfileContext context = new ProfileContextServiceImpl(storage).current("u1", null);

    assertTrue(context.getProfile().isEmpty());
    assertEquals("", context.getSummary());
  }

  /**
   * 验证 ProfileContextServiceImpl 中简历的失败恢复、超时与降级边界。
   */
  @Test
  void resumeReadFailureShouldDegradeWithoutResume() {
    ResumeStorageService storage = mock(ResumeStorageService.class);
    Map<String, Object> parsed = new LinkedHashMap<String, Object>();
    parsed.put("name", "李四");
    when(storage.getJobProfileOrEmpty("u1")).thenReturn(envelope(parsed));
    when(storage.get("r1", "u1")).thenThrow(new IllegalStateException("storage error"));

    UserProfileContext context = new ProfileContextServiceImpl(storage).current("u1", "r1");

    assertEquals("李四", context.getProfile().get("name").asText());
    assertFalse(context.getProfile().has("current_resume"));
  }

  /**
   * 验证 ProfileContextServiceImpl 中简历的权限与租户隔离边界。
   */
  @Test
  void unauthorizedResumeShouldBeIgnoredWhileKeepingJobProfile() {
    ResumeStorageService storage = mock(ResumeStorageService.class);
    Map<String, Object> parsed = new LinkedHashMap<String, Object>();
    parsed.put("name", "王五");
    when(storage.getJobProfileOrEmpty("u1")).thenReturn(envelope(parsed));
    when(storage.get("foreign-resume", "u1")).thenThrow(new IllegalArgumentException("无权操作该简历"));

    UserProfileContext context =
        new ProfileContextServiceImpl(storage).current("u1", "foreign-resume");

    assertEquals("王五", context.getProfile().get("name").asText());
    assertFalse(context.getProfile().has("current_resume"));
    assertTrue(context.getSummary().contains("姓名：王五"));
  }

  /**
   * 验证 ProfileContextServiceImpl 中简历的核心业务契约。
   */
  @Test
  void currentResumeShouldBeAttachedWhenPresent() {
    ResumeStorageService storage = mock(ResumeStorageService.class);
    when(storage.getJobProfileOrEmpty("u1"))
        .thenReturn(envelope(new LinkedHashMap<String, Object>()));
    ResumeRecord record = new ResumeRecord();
    Map<String, Object> resumeParsed = new LinkedHashMap<String, Object>();
    resumeParsed.put("projects", Arrays.asList("job-buddy"));
    record.setParsed(resumeParsed);
    when(storage.get("r1", "u1")).thenReturn(record);

    UserProfileContext context = new ProfileContextServiceImpl(storage).current("u1", "r1");

    assertEquals(JSON.toTree(resumeParsed), context.getProfile().get("current_resume"));
  }

  /**
   * 验证 ProfileContextServiceImpl 的数量、长度与分页边界。
   */
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

  /**
   * 验证响应封装。
   *
   * @param parsed 解析结果
   * @return 模拟下游响应
   */
  private ResumeSummaryResponse envelope(Map<String, Object> parsed) {
    ResumeSummaryResponse profile = new ResumeSummaryResponse();
    profile.setParsed(JSON.toTree(parsed));
    return profile;
  }
}
