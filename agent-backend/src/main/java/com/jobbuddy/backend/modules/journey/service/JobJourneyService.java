package com.jobbuddy.backend.modules.journey.service;

import com.jobbuddy.backend.modules.journey.dto.request.JobTargetRequest;
import com.jobbuddy.backend.modules.journey.dto.request.JourneyAnalysisRequest;
import com.jobbuddy.backend.modules.journey.dto.request.JourneyRecordRequest;
import com.jobbuddy.backend.modules.journey.dto.response.JobTargetResponse;
import com.jobbuddy.backend.modules.journey.dto.response.JourneyAnalysisResponse;
import com.jobbuddy.backend.modules.journey.dto.response.JourneyRecordResponse;
import java.util.List;

/**
 * 管理用户级求职目标、旅程记录与确定性进展分析。
 */
public interface JobJourneyService {
  /**
   * 获取当前用户的求职目标。
   *
   * @param userId 用户标识
   * @return 目标
   */
  JobTargetResponse getTarget(String userId);

  /**
   * 新增或更新当前用户的求职目标。
   *
   * @param userId 用户标识
   * @param request 请求对象
   * @return 保存后的目标
   */
  JobTargetResponse saveTarget(String userId, JobTargetRequest request);

  /**
   * 查询记录列表。
   *
   * @param userId 用户标识
   * @param keyword 关键词
   * @param status 状态
   * @param result 执行结果
   * @return 记录列表
   */
  List<JourneyRecordResponse> listRecords(
      String userId, String keyword, String status, String result);

  /**
   * 获取当前用户所属的求职记录。
   *
   * @param recordId 记录标识
   * @param userId 用户标识
   * @return 记录
   */
  JourneyRecordResponse getRecord(String recordId, String userId);

  /**
   * 新增或更新当前用户的求职记录。
   *
   * @param userId 用户标识
   * @param request 请求对象
   * @param recordId 记录标识
   * @return 保存后的记录
   */
  JourneyRecordResponse saveRecord(String userId, JourneyRecordRequest request, String recordId);

  /**
   * 删除当前用户所属的求职记录。
   *
   * @param recordId 记录标识
   * @param userId 用户标识
   */
  void deleteRecord(String recordId, String userId);

  /**
   * 分析进度。
   *
   * @param userId 用户标识
   * @param request 请求对象
   * @return 进度分析结果
   */
  JourneyAnalysisResponse analyzeProgress(String userId, JourneyAnalysisRequest request);
}
