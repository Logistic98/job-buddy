package com.jobbuddy.backend.modules.resume.dto.request;

import lombok.Data;

@Data
public class ResumeWriterVersionCreateRequest {
  /** 版本来源:manual / auto / import_backup / restore_backup。 */
  private String source;

  /** 版本说明,可为空,为空时按来源生成默认标题。 */
  private String title;

  /** 关联的简历库记录 ID,可为空。 */
  private String resumeId;

  /** 撰写器 writerState 全量快照 JSON。 */
  private String snapshot;
}
