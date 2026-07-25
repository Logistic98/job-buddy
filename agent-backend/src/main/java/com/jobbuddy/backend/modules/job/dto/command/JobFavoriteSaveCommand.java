package com.jobbuddy.backend.modules.job.dto.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * 动态岗位快照的显式业务命令，载荷来自 Boss/Runtime 外部 JSON。
 */
public class JobFavoriteSaveCommand {
  private final JsonNode jobSnapshot;

  /**
   * 创建收藏岗位保存命令实例。
   *
   * @param jobSnapshot 岗位快照
   */
  private JobFavoriteSaveCommand(JsonNode jobSnapshot) {
    this.jobSnapshot =
        jobSnapshot == null ? JsonNodeFactory.instance.objectNode() : jobSnapshot.deepCopy();
  }

  /**
   * 根据源数据创建对象。
   *
   * @param jobSnapshot 岗位快照
   * @return 创建后的对象
   */
  public static JobFavoriteSaveCommand from(JsonNode jobSnapshot) {
    return new JobFavoriteSaveCommand(jobSnapshot);
  }

  /**
   * 创建空结果对象。
   *
   * @return 空结果对象
   */
  public static JobFavoriteSaveCommand empty() {
    return new JobFavoriteSaveCommand(null);
  }

  /**
   * 判断是否空值。
   *
   * @return 是否未包含可保存内容
   */
  public boolean isEmpty() {
    return jobSnapshot.isEmpty();
  }

  /**
   * 生成当前数据快照。
   *
   * @return 数据快照
   */
  public JsonNode snapshot() {
    return jobSnapshot.deepCopy();
  }
}
