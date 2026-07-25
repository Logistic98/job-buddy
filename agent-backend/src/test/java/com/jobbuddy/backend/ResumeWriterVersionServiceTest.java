package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.modules.resume.dto.request.ResumeWriterRestoreRequest;
import com.jobbuddy.backend.modules.resume.dto.request.ResumeWriterVersionCreateRequest;
import com.jobbuddy.backend.modules.resume.dto.response.ResumeWriterVersionResponse;
import com.jobbuddy.backend.modules.resume.mapper.ResumeWriterVersionMapper;
import com.jobbuddy.backend.modules.resume.service.ResumeWriterVersionService;
import com.jobbuddy.backend.modules.resume.service.impl.ResumeWriterVersionServiceImpl;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 验证 ResumeWriterVersionService 的核心行为、异常路径与边界条件。
 */
class ResumeWriterVersionServiceTest {
  /**
   * 验证 ResumeWriterVersionService 的数量、长度与分页边界。
   */
  @Test
  void createAssignsIncrementingVersionNoAndTrimsToLimit() {
    ResumeWriterVersionMapper mapper = mock(ResumeWriterVersionMapper.class);
    when(mapper.maxVersionNo("tenant-a", "user-a")).thenReturn(30L);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setResumeWriterVersionLimit(12);
    ResumeWriterVersionServiceImpl service = new ResumeWriterVersionServiceImpl(mapper, properties);
    ResumeWriterVersionResponse created =
        service.create(
            "tenant-a",
            "user-a",
            create(
                "resume_1",
                ResumeWriterVersionService.SOURCE_MANUAL,
                "改了项目经历",
                "{\"markdown\":\"x\"}"));
    assertEquals(31L, created.getVersionNo());
    assertEquals("改了项目经历", created.getTitle());
    assertNull(created.getSnapshotJson());
    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass((Class) Map.class);
    verify(mapper).insertVersion(captor.capture());
    assertEquals("{\"markdown\":\"x\"}", captor.getValue().get("snapshotJson"));
    verify(mapper).deleteBeyondLimit("tenant-a", "user-a", 12);
  }

  /**
   * 验证 ResumeWriterVersionService 的输入校验与拒绝边界。
   */
  @Test
  void createRejectsUnknownSourceAndEmptySnapshot() {
    ResumeWriterVersionServiceImpl service = newService(mock(ResumeWriterVersionMapper.class));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.create("tenant-a", "user-a", create(null, "unknown", null, "{}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.create(
                "tenant-a",
                "user-a",
                create(null, ResumeWriterVersionService.SOURCE_MANUAL, null, "  ")));
  }

  /**
   * 验证 ResumeWriterVersionService 的输入校验与拒绝边界。
   */
  @Test
  void createRejectsOversizedSnapshot() {
    ResumeWriterVersionMapper mapper = mock(ResumeWriterVersionMapper.class);
    ResumeWriterVersionServiceImpl service = newService(mapper);
    StringBuilder large = new StringBuilder();
    for (int i = 0; i < 2 * 1024 * 1024 + 1; i++) large.append('a');
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.create(
                "tenant-a",
                "user-a",
                create(null, ResumeWriterVersionService.SOURCE_AUTO, null, large.toString())));
    verify(mapper, never()).insertVersion(anyMap());
  }

  /**
   * 验证 ResumeWriterVersionService 的持久化与状态变更规则。
   */
  @Test
  void restoreBacksUpCurrentStateBeforeReturningTarget() {
    ResumeWriterVersionMapper mapper = mock(ResumeWriterVersionMapper.class);
    Map<String, Object> target = new LinkedHashMap<>();
    target.put("versionId", "rwv_target");
    target.put("snapshotJson", "{\"markdown\":\"old\"}");
    when(mapper.findByIdAndOwner("tenant-a", "user-a", "rwv_target")).thenReturn(target);
    when(mapper.maxVersionNo("tenant-a", "user-a")).thenReturn(5L);
    ResumeWriterVersionResponse restored =
        newService(mapper)
            .restore(
                "tenant-a",
                "user-a",
                "rwv_target",
                restore("resume_1", "{\"markdown\":\"current\"}"));
    assertEquals("rwv_target", restored.getVersionId());
    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass((Class) Map.class);
    verify(mapper).insertVersion(captor.capture());
    assertEquals(ResumeWriterVersionService.SOURCE_RESTORE_BACKUP, captor.getValue().get("source"));
  }

  /**
   * 验证 ResumeWriterVersionService 的持久化与状态变更规则。
   */
  @Test
  void restoreWithoutCurrentSnapshotSkipsBackup() {
    ResumeWriterVersionMapper mapper = mock(ResumeWriterVersionMapper.class);
    Map<String, Object> target = new LinkedHashMap<>();
    target.put("versionId", "rwv_target");
    when(mapper.findByIdAndOwner("tenant-a", "user-a", "rwv_target")).thenReturn(target);
    newService(mapper).restore("tenant-a", "user-a", "rwv_target", null);
    verify(mapper, never()).insertVersion(anyMap());
  }

  /**
   * 验证 ResumeWriterVersionService 的权限与租户隔离边界。
   */
  @Test
  void crossOwnerVersionAccessFails() {
    ResumeWriterVersionMapper mapper = mock(ResumeWriterVersionMapper.class);
    when(mapper.findByIdAndOwner("tenant-b", "user-b", "rwv_target")).thenReturn(null);
    when(mapper.deleteByIdAndOwner("tenant-b", "user-b", "rwv_target")).thenReturn(0);
    ResumeWriterVersionServiceImpl service = newService(mapper);
    assertThrows(
        IllegalArgumentException.class, () -> service.get("tenant-b", "user-b", "rwv_target"));
    assertThrows(
        IllegalArgumentException.class, () -> service.delete("tenant-b", "user-b", "rwv_target"));
    assertThrows(IllegalArgumentException.class, () -> service.list(null, "user-b"));
  }

  /**
   * 验证 ResumeWriterVersionService 的核心业务契约。
   */
  @Test
  void autoTitleFallsBackBySource() {
    ResumeWriterVersionMapper mapper = mock(ResumeWriterVersionMapper.class);
    ResumeWriterVersionResponse created =
        newService(mapper)
            .create(
                "tenant-a",
                "user-a",
                create(null, ResumeWriterVersionService.SOURCE_IMPORT_BACKUP, "  ", "{}"));
    assertEquals(1L, created.getVersionNo());
    assertEquals("导入前自动备份", created.getTitle());
    assertNull(created.getResumeId());
  }

  /**
   * 验证创建。
   *
   * @param resumeId 简历标识
   * @param source 源数据
   * @param title 标题
   * @param snapshot 快照
   * @return 创建后的记忆记录
   */
  private ResumeWriterVersionCreateRequest create(
      String resumeId, String source, String title, String snapshot) {
    ResumeWriterVersionCreateRequest request = new ResumeWriterVersionCreateRequest();
    request.setResumeId(resumeId);
    request.setSource(source);
    request.setTitle(title);
    request.setSnapshot(snapshot);
    return request;
  }

  /**
   * 验证恢复。
   *
   * @param resumeId 简历标识
   * @param snapshot 快照
   * @return 版本恢复请求
   */
  private ResumeWriterRestoreRequest restore(String resumeId, String snapshot) {
    ResumeWriterRestoreRequest request = new ResumeWriterRestoreRequest();
    request.setCurrentResumeId(resumeId);
    request.setCurrentSnapshot(snapshot);
    return request;
  }

  /**
   * 验证新建服务。
   *
   * @param mapper 数据映射
   * @return 服务
   */
  private ResumeWriterVersionServiceImpl newService(ResumeWriterVersionMapper mapper) {
    return new ResumeWriterVersionServiceImpl(mapper, new JobBuddyProperties());
  }
}
