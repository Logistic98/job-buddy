package com.jobbuddy.backend.modules.job.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Boss 选择性导入汇总；允许部分成功，并明确是否因登录或风控停止。
 */
public class BossFavoriteImportResponse {
  private final int importedCount;
  private final int existingCount;
  private final int failedCount;
  private final int unprocessedCount;
  private final boolean stopped;
  private final boolean authRequired;
  private final String stoppedReason;
  private final JsonNode authData;
  private final List<BossFavoriteImportItemResponse> items;
  private final List<JobFavoriteResponse> favorites;

  /**
   * 创建 Boss 收藏岗位导入响应实例。
   *
   * @param importedCount 已导入数量
   * @param existingCount 已存在数量
   * @param failedCount 失败数量
   * @param unprocessedCount 未处理数量
   * @param stopped 是否提前停止
   * @param authRequired 认证必需
   * @param stoppedReason 停止原因
   * @param authData 认证数据
   * @param items 数据项列表
   * @param favorites 收藏岗位列表
   */
  public BossFavoriteImportResponse(
      int importedCount,
      int existingCount,
      int failedCount,
      int unprocessedCount,
      boolean stopped,
      boolean authRequired,
      String stoppedReason,
      JsonNode authData,
      List<BossFavoriteImportItemResponse> items,
      List<JobFavoriteResponse> favorites) {
    this.importedCount = importedCount;
    this.existingCount = existingCount;
    this.failedCount = failedCount;
    this.unprocessedCount = unprocessedCount;
    this.stopped = stopped;
    this.authRequired = authRequired;
    this.stoppedReason = stoppedReason;
    this.authData = authData == null ? null : authData.deepCopy();
    this.items = Collections.unmodifiableList(new ArrayList<BossFavoriteImportItemResponse>(items));
    this.favorites = Collections.unmodifiableList(new ArrayList<JobFavoriteResponse>(favorites));
  }

  /**
   * 获取已导入数量。
   *
   * @return 已导入数量
   */
  public int getImportedCount() {
    return importedCount;
  }

  /**
   * 获取已有数量。
   *
   * @return 已存在数量
   */
  public int getExistingCount() {
    return existingCount;
  }

  /**
   * 获取失败数量。
   *
   * @return 失败数量
   */
  public int getFailedCount() {
    return failedCount;
  }

  /**
   * 获取未处理数量。
   *
   * @return 未处理数量
   */
  public int getUnprocessedCount() {
    return unprocessedCount;
  }

  /**
   * 判断是否停止。
   *
   * @return 是否已停止导入
   */
  public boolean isStopped() {
    return stopped;
  }

  /**
   * 判断是否认证必需。
   *
   * @return 是否需要重新认证
   */
  public boolean isAuthRequired() {
    return authRequired;
  }

  /**
   * 获取停止原因。
   *
   * @return 停止原因
   */
  public String getStoppedReason() {
    return stoppedReason;
  }

  /**
   * 获取认证数据。
   *
   * @return 认证数据
   */
  public JsonNode getAuthData() {
    return authData == null ? null : authData.deepCopy();
  }

  /**
   * 获取数据项列表。
   *
   * @return 数据项列表
   */
  public List<BossFavoriteImportItemResponse> getItems() {
    return items;
  }

  /**
   * 获取收藏岗位列表。
   *
   * @return 收藏岗位列表
   */
  public List<JobFavoriteResponse> getFavorites() {
    return favorites;
  }
}
