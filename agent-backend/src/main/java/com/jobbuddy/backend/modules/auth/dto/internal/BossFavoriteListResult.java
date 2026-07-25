package com.jobbuddy.backend.modules.auth.dto.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Boss 收藏列表单页结果。岗位卡片属于外部不稳定 JSON 边界，不在业务层扩散 Map。
 */
public class BossFavoriteListResult {
  private final List<JsonNode> jobs;
  private final int page;
  private final boolean hasMore;
  private final int totalCount;
  private final int totalPages;
  private final JsonNode rate;

  /**
   * 创建 Boss 收藏岗位列表结果实例。
   *
   * @param jobs 岗位列表
   * @param page 分页
   * @param hasMore 是否还有下一页
   * @param totalCount 总数数量
   * @param totalPages 总数分页
   * @param rate 完成比例
   */
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

  /**
   * 获取岗位列表。
   *
   * @return 岗位列表
   */
  public List<JsonNode> getJobs() {
    return jobs;
  }

  /**
   * 获取分页。
   *
   * @return 页码
   */
  public int getPage() {
    return page;
  }

  /**
   * 判断是否还有更多数据。
   *
   * @return 是否还有下一页
   */
  public boolean isHasMore() {
    return hasMore;
  }

  /**
   * 获取记录总数。
   *
   * @return 总数量
   */
  public int getTotalCount() {
    return totalCount;
  }

  /**
   * 获取总页数。
   *
   * @return 总页数
   */
  public int getTotalPages() {
    return totalPages;
  }

  /**
   * 获取 Boss 接口返回的限流信息。
   *
   * @return 限流信息副本
   */
  public JsonNode getRate() {
    return rate == null ? null : rate.deepCopy();
  }
}
