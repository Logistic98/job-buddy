package com.jobbuddy.backend.modules.job.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Boss 选择性导入汇总；允许部分成功，并明确是否因登录或风控停止。 */
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

  public int getImportedCount() {
    return importedCount;
  }

  public int getExistingCount() {
    return existingCount;
  }

  public int getFailedCount() {
    return failedCount;
  }

  public int getUnprocessedCount() {
    return unprocessedCount;
  }

  public boolean isStopped() {
    return stopped;
  }

  public boolean isAuthRequired() {
    return authRequired;
  }

  public String getStoppedReason() {
    return stoppedReason;
  }

  public JsonNode getAuthData() {
    return authData == null ? null : authData.deepCopy();
  }

  public List<BossFavoriteImportItemResponse> getItems() {
    return items;
  }

  public List<JobFavoriteResponse> getFavorites() {
    return favorites;
  }
}
