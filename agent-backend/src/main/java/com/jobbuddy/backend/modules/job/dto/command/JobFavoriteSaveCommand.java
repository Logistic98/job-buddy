package com.jobbuddy.backend.modules.job.dto.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/** 动态岗位快照的显式业务命令，载荷来自 Boss/Runtime 外部 JSON。 */
public class JobFavoriteSaveCommand {
  private final JsonNode jobSnapshot;

  private JobFavoriteSaveCommand(JsonNode jobSnapshot) {
    this.jobSnapshot =
        jobSnapshot == null ? JsonNodeFactory.instance.objectNode() : jobSnapshot.deepCopy();
  }

  public static JobFavoriteSaveCommand from(JsonNode jobSnapshot) {
    return new JobFavoriteSaveCommand(jobSnapshot);
  }

  public static JobFavoriteSaveCommand empty() {
    return new JobFavoriteSaveCommand(null);
  }

  public boolean isEmpty() {
    return jobSnapshot.isEmpty();
  }

  public JsonNode snapshot() {
    return jobSnapshot.deepCopy();
  }
}
