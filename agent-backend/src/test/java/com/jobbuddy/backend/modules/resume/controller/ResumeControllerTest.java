package com.jobbuddy.backend.modules.resume.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.result.ApiResponse;
import com.jobbuddy.backend.common.security.AuthenticatedUser;
import com.jobbuddy.backend.common.security.AuthenticatedUserContext;
import com.jobbuddy.backend.modules.analysis.service.AnalysisTaskService;
import com.jobbuddy.backend.modules.resume.dto.response.ResumeSummaryResponse;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.mock.web.MockMultipartFile;

class ResumeControllerTest {

  /**
   * 验证 ResumeController 中简历的持久化与状态变更规则。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void uploadReturnsStoredResumeWithoutWaitingForParsingOrAnalysis() throws Exception {
    ResumeStorageService storageService = mock(ResumeStorageService.class);
    AnalysisTaskService analysisTaskService = mock(AnalysisTaskService.class);
    ResumeController controller = new ResumeController(storageService, analysisTaskService);
    HttpServletRequest request = authenticatedRequest();
    MockMultipartFile file =
        new MockMultipartFile("file", "fallback.pdf", "application/pdf", new byte[] {1, 2, 3, 4});
    ResumeRecord stored = new ResumeRecord();
    stored.setResumeId("resume-1");
    stored.setParseStatus("pending");
    ResumeSummaryResponse summary = new ResumeSummaryResponse();
    summary.setResumeId("resume-1");
    summary.setOriginalName("中文简历.pdf");
    summary.setParseStatus("pending");
    when(storageService.upload(file, "中文简历.pdf", "tenant-1", "user-1")).thenReturn(stored);
    when(storageService.summarize(stored)).thenReturn(summary);

    ApiResponse<ResumeSummaryResponse> response =
        controller.upload(file, request, "%E4%B8%AD%E6%96%87%E7%AE%80%E5%8E%86.pdf", "session-1");

    assertEquals(200, response.getCode());
    assertEquals("resume-1", response.getData().getResumeId());
    assertEquals("pending", response.getData().getParseStatus());
    verify(storageService).upload(file, "中文简历.pdf", "tenant-1", "user-1");
    verify(storageService, never()).parseSync(anyString(), any(), anyString(), anyString());
    verifyNoInteractions(analysisTaskService);
  }

  /**
   * 验证删除简历前先取消同一资源的活动分析任务。
   */
  @Test
  void deleteCancelsActiveAnalysisBeforeRemovingStoredResume() {
    ResumeStorageService storageService = mock(ResumeStorageService.class);
    AnalysisTaskService analysisTaskService = mock(AnalysisTaskService.class);
    ResumeController controller = new ResumeController(storageService, analysisTaskService);

    controller.delete("resume-1", authenticatedRequest());

    InOrder lifecycle = inOrder(analysisTaskService, storageService);
    lifecycle
        .verify(analysisTaskService)
        .cancelActiveResource("tenant-1", "user-1", AnalysisTaskService.TYPE_RESUME, "resume-1");
    lifecycle.verify(storageService).delete("resume-1", "tenant-1", "user-1");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "pdf,application/pdf",
    "txt,text/plain;charset=UTF-8",
    "md,text/plain;charset=UTF-8",
    "bin,application/octet-stream"
  })
  void fileResponsesPreserveContentAndDisposition(String suffix, String mediaType)
      throws Exception {
    ResumeStorageService storage = mock(ResumeStorageService.class);
    ResumeController controller = new ResumeController(storage, mock(AnalysisTaskService.class));
    ResumeRecord record = new ResumeRecord();
    record.setResumeId("resume");
    record.setTenantId("tenant-1");
    record.setUserId("user-1");
    record.setOriginalName("sample." + suffix);
    record.setSuffix(suffix);
    record.setSizeBytes(3);
    when(storage.get("resume", "tenant-1", "user-1")).thenReturn(record);
    when(storage.openOriginalFile("resume", "tenant-1", "user-1"))
        .thenAnswer(invocation -> new java.io.ByteArrayInputStream(new byte[] {1, 2, 3}));
    var preview = controller.preview("resume", authenticatedRequest());
    var download = controller.download("resume", authenticatedRequest());
    assertEquals(
        org.springframework.http.MediaType.parseMediaType(mediaType),
        preview.getHeaders().getContentType());
    assertEquals(3, preview.getHeaders().getContentLength());
    assertEquals("inline", preview.getHeaders().getContentDisposition().getType());
    assertEquals("attachment", download.getHeaders().getContentDisposition().getType());
    org.junit.jupiter.api.Assertions.assertArrayEquals(
        new byte[] {1, 2, 3}, download.getBody().getInputStream().readAllBytes());
  }

  @Test
  void absentFileIsRejectedBeforeOpeningStorage() {
    ResumeStorageService storage = mock(ResumeStorageService.class);
    ResumeController controller = new ResumeController(storage, mock(AnalysisTaskService.class));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> controller.preview("missing", authenticatedRequest()));
    verify(storage, never()).openOriginalFile(any(), any(), any());
  }

  @Test
  void thumbnailAndAssetUseAuthenticatedOwner() throws Exception {
    ResumeStorageService storage = mock(ResumeStorageService.class);
    ResumeController controller = new ResumeController(storage, mock(AnalysisTaskService.class));
    when(storage.thumbnail("resume", "tenant-1", "user-1")).thenReturn(new byte[] {7});
    var thumbnail = controller.thumbnail("resume", authenticatedRequest());
    assertEquals(
        org.springframework.http.MediaType.IMAGE_PNG, thumbnail.getHeaders().getContentType());
    org.junit.jupiter.api.Assertions.assertArrayEquals(
        new byte[] {7}, thumbnail.getBody().getInputStream().readAllBytes());
    when(storage.openAsset("asset", "user-1"))
        .thenReturn(new java.io.ByteArrayInputStream(new byte[] {8}));
    when(storage.assetContentType("asset", "user-1")).thenReturn("image/webp");
    var asset = controller.asset("asset", authenticatedRequest());
    assertEquals(
        org.springframework.http.MediaType.parseMediaType("image/webp"),
        asset.getHeaders().getContentType());
    org.junit.jupiter.api.Assertions.assertArrayEquals(
        new byte[] {8}, asset.getBody().getInputStream().readAllBytes());
  }

  @Test
  void readAndAnalysisEndpointsForwardOwnerAndSession() {
    ResumeStorageService storage = mock(ResumeStorageService.class);
    AnalysisTaskService tasks = mock(AnalysisTaskService.class);
    ResumeController controller = new ResumeController(storage, tasks);
    HttpServletRequest request = authenticatedRequest();
    controller.list(request);
    controller.profile(request);
    controller.get("resume", request);
    controller.analyze("resume", request, "session");
    verify(storage).list("tenant-1", "user-1");
    verify(storage).getJobProfileOrEmpty("tenant-1", "user-1");
    verify(storage).get("resume", "tenant-1", "user-1");
    verify(storage).analyzeSync("resume", "session", "tenant-1", "user-1");
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> controller.startAnalysisTask(null, request));
    verifyNoInteractions(tasks);
  }

  @Test
  void profileAndAssetWritesPreserveOwnerScope() throws Exception {
    ResumeStorageService storage = mock(ResumeStorageService.class);
    ResumeController controller = new ResumeController(storage, mock(AnalysisTaskService.class));
    HttpServletRequest request = authenticatedRequest();
    MockMultipartFile file =
        new MockMultipartFile("file", "photo.png", "image/png", new byte[] {1});
    controller.saveProfile(request, null);
    controller.generateProfileSummary(null, "session");
    controller.syncBossOnlineResume(request);
    controller.updateParsed("resume", request, null);
    controller.uploadAsset(file, request);
    verify(storage).saveJobProfile("tenant-1", "user-1", null);
    verify(storage).generateJobProfileSummary(null, "session");
    verify(storage).syncBossOnlineResume("tenant-1", "user-1");
    verify(storage).updateParsed("resume", null, "tenant-1", "user-1");
    verify(storage).uploadAsset(file, "tenant-1", "user-1");
  }

  /**
   * 构造带认证身份的模拟请求。
   *
   * @return 带认证上下文的测试请求
   */
  private HttpServletRequest authenticatedRequest() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    AuthenticatedUser user = new AuthenticatedUser("user-1", "tester", "Tester", "user");
    user.setTenantId("tenant-1");
    when(request.getAttribute(AuthenticatedUserContext.USER_ATTRIBUTE)).thenReturn(user);
    return request;
  }
}
