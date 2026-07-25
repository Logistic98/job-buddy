package com.jobbuddy.backend.modules.job.service;

import com.jobbuddy.backend.modules.analysis.dto.AnalysisPartialResult;
import com.jobbuddy.backend.modules.job.dto.command.JobFavoriteAnalysisCommand;
import com.jobbuddy.backend.modules.job.dto.command.JobFavoriteSaveCommand;
import com.jobbuddy.backend.modules.job.dto.request.BossFavoriteImportRequest;
import com.jobbuddy.backend.modules.job.dto.response.BossFavoriteImportResponse;
import com.jobbuddy.backend.modules.job.dto.response.BossFavoritePreviewResponse;
import com.jobbuddy.backend.modules.job.dto.response.JobFavoriteResponse;
import java.util.List;
import java.util.function.Consumer;

/**
 * 定义岗位收藏岗位服务契约。
 */
public interface JobFavoriteService {
  /**
   * 查询收藏项列表。
   *
   * @param userId 用户标识
   * @return 收藏项列表
   */
  List<JobFavoriteResponse> listFavorites(String userId);

  /**
   * 保存收藏岗位。
   *
   * @param userId 用户标识
   * @param command 业务命令
   */
  void saveFavorite(String userId, JobFavoriteSaveCommand command);

  /**
   * 预览 Boss 收藏项。
   *
   * @param userId 用户标识
   * @param page 页码
   * @param forceRefresh 是否强制刷新
   * @return Boss 收藏岗位预览结果
   */
  BossFavoritePreviewResponse previewBossFavorites(String userId, int page, boolean forceRefresh);

  /**
   * 导入 Boss 收藏项。
   *
   * @param userId 用户标识
   * @param request 请求对象
   * @return Boss 收藏岗位导入结果
   */
  BossFavoriteImportResponse importBossFavorites(String userId, BossFavoriteImportRequest request);

  /**
   * 移除收藏岗位。
   *
   * @param userId 用户标识
   * @param jobKey 岗位键
   */
  void removeFavorite(String userId, String jobKey);

  /**
   * 分析收藏岗位。
   *
   * @param userId 用户标识
   * @param command 业务命令
   * @return 分析后的收藏岗位
   */
  JobFavoriteResponse analyzeFavorite(String userId, JobFavoriteAnalysisCommand command);

  /**
   * 分析岗位。
   *
   * @param userId 用户标识
   * @param command 业务命令
   * @param resumeId 简历标识
   * @return 分析后的岗位
   */
  JobFavoriteResponse analyzeJob(String userId, JobFavoriteSaveCommand command, String resumeId);

  /**
   * 分析岗位增量结果。
   *
   * @param userId 用户标识
   * @param command 业务命令
   * @param resumeId 简历标识
   * @param consumer 结果消费函数
   * @return 岗位增量分析结果
   */
  JobFavoriteResponse analyzeJobIncrementally(
      String userId,
      JobFavoriteSaveCommand command,
      String resumeId,
      Consumer<AnalysisPartialResult> consumer);
}
