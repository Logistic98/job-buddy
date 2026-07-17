package com.jobbuddy.backend.modules.auth.dto.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Boss 收藏列表单页结果。岗位卡片属于外部不稳定 JSON 边界，不在业务层扩散 Map。 */
public class BossFavoriteListResult {
  private final List<JsonNode> jobs;
  private final int page;
  private final boolean hasMore;
  private final int totalCount;
  private final int totalPages;
  private final JsonNode rate;

  public BossFavoriteListResult(
      List<JsonNode> jobs,
      int page,
      boolean hasMore,
      int totalCount,
      int totalPages,
      JsonNode rate) {
    this.jobs =
        jobs == null
            ? Collections.<JsonNode>emptyList()
            : Collections.unmodifiableList(new ArrayList<JsonNode>(jobs));
    this.page = page;
    this.hasMore = hasMore;
    this.totalCount = totalCount;
    this.totalPages = Math.max(1, totalPages);
    this.rate = rate == null ? null : rate.deepCopy();
  }

  public List<JsonNode> getJobs() {
    return jobs;
  }

  public int getPage() {
    return page;
  }

  public boolean isHasMore() {
    return hasMore;
  }

  public int getTotalCount() {
    return totalCount;
  }

  public int getTotalPages() {
    return totalPages;
  }

  public JsonNode getRate() {
    return rate == null ? null : rate.deepCopy();
  }
}
