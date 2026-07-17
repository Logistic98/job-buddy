package com.jobbuddy.backend.modules.resume.service;

import com.jobbuddy.backend.modules.resume.dto.request.ResumeWriterRestoreRequest;
import com.jobbuddy.backend.modules.resume.dto.request.ResumeWriterVersionCreateRequest;
import com.jobbuddy.backend.modules.resume.dto.response.ResumeWriterVersionResponse;
import java.util.List;

public interface ResumeWriterVersionService {
  String SOURCE_MANUAL = "manual";
  String SOURCE_AUTO = "auto";
  String SOURCE_IMPORT_BACKUP = "import_backup";
  String SOURCE_RESTORE_BACKUP = "restore_backup";

  List<ResumeWriterVersionResponse> list(String tenantId, String userId);

  ResumeWriterVersionResponse get(String tenantId, String userId, String versionId);

  ResumeWriterVersionResponse create(
      String tenantId, String userId, ResumeWriterVersionCreateRequest request);

  ResumeWriterVersionResponse restore(
      String tenantId, String userId, String versionId, ResumeWriterRestoreRequest request);

  void delete(String tenantId, String userId, String versionId);
}
