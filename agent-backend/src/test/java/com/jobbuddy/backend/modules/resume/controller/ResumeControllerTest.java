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

/**
 * 验证 ResumeController 的核心行为、异常路径与边界条件。
 */
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
