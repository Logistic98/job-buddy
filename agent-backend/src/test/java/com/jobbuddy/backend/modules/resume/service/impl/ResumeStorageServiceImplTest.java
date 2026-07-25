package com.jobbuddy.backend.modules.resume.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.auth.service.BossCliService;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeToolResult;
import com.jobbuddy.backend.modules.chat.service.RuntimeToolClient;
import com.jobbuddy.backend.modules.resume.dto.response.ResumeProfileSummaryResponse;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.repository.ResumeRecordRepository;
import com.jobbuddy.backend.modules.resume.storage.ResumeObjectStorage;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * 验证 ResumeStorageServiceImpl 的核心行为、异常路径与边界条件。
 */
class ResumeStorageServiceImplTest {

  private final JsonCodec jsonCodec = new JsonCodec();
  private final RuntimeToolClient toolClient = mock(RuntimeToolClient.class);
  private final ResumeStorageServiceImpl service =
      new ResumeStorageServiceImpl(
          new JobBuddyProperties(),
          toolClient,
          mock(ResumeRecordRepository.class),
          mock(ResumeObjectStorage.class),
          mock(BossCliService.class),
          jsonCodec);

  /**
   * 验证 ResumeStorageServiceImpl 中岗位的文件解析与存储边界。
   */
  @Test
  void generateJobProfileSummaryReadsBusinessSummaryFromToolOutput() {
    Map<String, Object> output = new LinkedHashMap<String, Object>();
    output.put("summary", "具备六年 Java 与 Python 研发经验，聚焦上海大模型应用开发岗位，期望月薪 40-50k。");
    output.put("highlights", Arrays.asList("五年平台研发经验", "大模型应用开发"));
    output.put("missing_fields", Arrays.asList("硬性排除项"));

    Map<String, Object> toolResult = new LinkedHashMap<String, Object>();
    toolResult.put("success", true);
    toolResult.put("summary", "job_profile_summary 执行成功");
    toolResult.put("output", output);
    when(toolClient.invoke(eq("job_profile_summary"), any(), any(), anyString()))
        .thenReturn(runtimeToolResult(toolResult));

    ResumeProfileSummaryResponse response =
        service.generateJobProfileSummary(profile("当前人工摘要"), "session-1");

    assertEquals("当前人工摘要", response.getOldSummary());
    assertEquals(output.get("summary"), response.getNewSummary());
    assertEquals("五年平台研发经验", response.getHighlights().get(0).asText());
    assertEquals("硬性排除项", response.getMissingFields().get(0).asText());
    assertEquals("AI", response.getProvider());
  }

  /**
   * 验证 ResumeStorageServiceImpl 中岗位的输入校验与拒绝边界。
   */
  @Test
  void generateJobProfileSummaryFallsBackWhenToolOutputIsInvalid() {
    Map<String, Object> toolResult = new LinkedHashMap<String, Object>();
    toolResult.put("success", true);
    toolResult.put("summary", "job_profile_summary 执行成功");
    when(toolClient.invoke(eq("job_profile_summary"), any(), any(), anyString()))
        .thenReturn(runtimeToolResult(toolResult));

    ResumeProfileSummaryResponse response =
        service.generateJobProfileSummary(profile("当前人工摘要"), "session-1");

    assertEquals("当前人工摘要", response.getOldSummary());
    assertNotEquals("job_profile_summary 执行成功", response.getNewSummary());
    assertEquals("fallback", response.getProvider());
  }

  /**
   * 验证 ResumeStorageServiceImpl 中简历的数量、长度与分页边界。
   */
  @Test
  void uploadReadsCurrentResumeSizeLimit() {
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setMaxResumeBytes(4);
    ResumeStorageServiceImpl sizeLimitedService =
        new ResumeStorageServiceImpl(
            properties,
            toolClient,
            mock(ResumeRecordRepository.class),
            mock(ResumeObjectStorage.class),
            mock(BossCliService.class),
            jsonCodec);
    MockMultipartFile file =
        new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[] {1, 2, 3, 4, 5});

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> sizeLimitedService.upload(file, "tenant-a", "user-a"));

    assertEquals("简历文件超出大小限制: 4 bytes", error.getMessage());
  }

  /**
   * 验证 ResumeStorageServiceImpl 的文件解析与存储边界。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void uploadPrefersExplicitUtf8OriginalNameOverMultipartHeaderName() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "????????-Java.pdf", "application/pdf", new byte[] {1, 2, 3, 4});

    ResumeRecord record = service.upload(file, "示例候选人-Java开发-求职简历.pdf", "tenant-a", "user-a");

    assertEquals("示例候选人-Java开发-求职简历.pdf", record.getOriginalName());
    assertEquals("pdf", record.getSuffix());
  }

  /**
   * 验证 ResumeStorageServiceImpl 的文件解析与存储边界。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void uploadRemovesClientPathFromExplicitOriginalName() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[] {1, 2, 3, 4});

    ResumeRecord record = service.upload(file, "C:\\fakepath\\中文简历.pdf", "tenant-a", "user-a");

    assertEquals("中文简历.pdf", record.getOriginalName());
  }

  /**
   * 验证运行时工具结果。
   *
   * @param value 待处理值
   * @return runtime 工具 Result
   */
  private RuntimeToolResult runtimeToolResult(Map<String, Object> value) {
    return RuntimeToolResult.fromJson(jsonCodec.toTree(value));
  }

  /**
   * 验证画像。
   *
   * @param summary 摘要
   * @return 测试简历画像
   */
  private com.fasterxml.jackson.databind.JsonNode profile(String summary) {
    Map<String, Object> profile = new LinkedHashMap<String, Object>();
    profile.put("summary", summary);
    profile.put("years_experience", "6年");
    profile.put("current_title", "Java 大模型应用开发");
    profile.put("skills", Arrays.asList("Java", "Python", "Spring AI"));

    Map<String, Object> expectations = new LinkedHashMap<String, Object>();
    expectations.put("position", "大模型应用开发岗");
    expectations.put("city", "上海");
    expectations.put("salary", "40-50k");
    profile.put("job_expectations", expectations);
    return jsonCodec.toTree(profile);
  }
}
