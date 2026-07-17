package com.jobbuddy.backend.modules.job.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 用户在 Boss 导入弹窗中明确勾选的岗位快照。 */
public class BossFavoriteImportRequest {
  private List<JsonNode> jobs = Collections.emptyList();

  public List<JsonNode> getJobs() {
    List<JsonNode> copies = new ArrayList<JsonNode>();
    for (JsonNode job : jobs) {
      if (job != null) copies.add(job.deepCopy());
    }
    return copies;
  }

  public void setJobs(List<JsonNode> jobs) {
    this.jobs = jobs == null ? Collections.<JsonNode>emptyList() : new ArrayList<JsonNode>(jobs);
  }
}
