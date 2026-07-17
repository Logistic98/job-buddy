package com.jobbuddy.backend.modules.resume.dto.request;

import lombok.Data;

@Data
public class ResumeWriterRestoreRequest {
  /** 回退前撰写器当前状态快照,非空时服务端先落一条 restore_backup。 */
  private String currentSnapshot;

  /** 当前快照关联的简历库记录 ID,可为空。 */
  private String currentResumeId;
}
