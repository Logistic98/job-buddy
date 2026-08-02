package com.jobbuddy.backend.modules.chat.service;

import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import java.util.List;
import java.util.Map;

/**
 * 衔接 Backend 确定性岗位流程、Boss Tool 数据与 Runtime 评分。
 *
 * <p>候选召回和业务过滤由 Backend 负责，Runtime 通过跨服务契约返回语义匹配结果。
 */
public interface JobRuntimeService {
  /**
   * 启动 Boss 登录。
   *
   * @param sessionId 会话标识
   * @return 启动后的 Boss 登录
   */
  Map<String, Object> startBossLogin(String sessionId);

  /**
   * 判断是否存在可用的 Boss 凭据。
   *
   * @return 是否存在可用状态 Boss 凭据
   */
  boolean hasUsableBossCredential();

  /**
   * 推荐岗位。
   *
   * @param intent 意图
   * @param sessionId 会话标识
   * @return 推荐岗位列表
   */
  List<Map<String, Object>> recommendJobs(IntentResult intent, String sessionId);

  /**
   * 推荐岗位。
   *
   * @param intent 意图
   * @param sessionId 会话标识
   * @param consumer 结果消费函数
   * @return 推荐岗位列表
   */
  List<Map<String, Object>> recommendJobs(
      IntentResult intent, String sessionId, JobProgressConsumer consumer);

  /**
   * 推荐岗位快速结果。
   *
   * @param intent 意图
   * @param sessionId 会话标识
   * @param consumer 结果消费函数
   * @return 快速推荐岗位列表
   */
  List<Map<String, Object>> recommendJobsFast(
      IntentResult intent, String sessionId, JobProgressConsumer consumer);

  /**
   * 返回调用方等待渐进式候选批次的超时上限。
   *
   * @return Boss 候选池超时秒数
   */
  int bossCandidatePoolTimeoutSeconds();

  /**
   * 对已有候选集评分并应用推荐质量门。
   *
   * @param resume 简历
   * @param jobs 岗位列表
   * @param sessionId 会话标识
   * @return 预筛后的推荐岗位
   */
  JobRecommendationResult prequalifyRecommendations(
      ResumeRecord resume, List<Map<String, Object>> jobs, String sessionId);

  /**
   * 首批候选未通过质量门时继续拉取后续页面。
   *
   * <p>续搜不得为凑足数量而降低已配置的分数或置信度要求。
   *
   * @param resume 简历
   * @param intent 意图
   * @param initialJobs 初始岗位列表
   * @param sessionId 会话标识
   * @return 支持续搜的预筛结果
   */
  JobRecommendationResult prequalifyRecommendationsWithContinuation(
      ResumeRecord resume,
      IntentResult intent,
      List<Map<String, Object>> initialJobs,
      String sessionId);

  /**
   * 匹配简历。
   *
   * @param resume 简历
   * @param jobs 岗位列表
   * @param sessionId 会话标识
   * @return 简历匹配结果
   */
  Map<String, Object> matchResume(
      ResumeRecord resume, List<Map<String, Object>> jobs, String sessionId);

  /**
   * 匹配简历章节。
   *
   * @param resume 简历
   * @param jobs 岗位列表
   * @param sessionId 会话标识
   * @param sections 简历章节列表
   * @return 简历章节匹配结果
   */
  Map<String, Object> matchResumeSections(
      ResumeRecord resume, List<Map<String, Object>> jobs, String sessionId, List<String> sections);

  /**
   * 使用岗位列表结构化信息匹配简历，不要求先加载完整职位描述。
   *
   * @param resume 简历
   * @param jobs 岗位列表
   * @param sessionId 会话标识
   * @param sections 简历章节列表
   * @return 简历章节匹配结果
   */
  Map<String, Object> matchResumeListEvidenceSections(
      ResumeRecord resume, List<Map<String, Object>> jobs, String sessionId, List<String> sections);

  /**
   * 渐进式召回期间接收稳定累计预览与最新 Boss 批次。
   */
  interface JobProgressConsumer {
    /**
     * 接收并处理输入。
     *
     * @param previewJobs 预览岗位列表
     * @param latestBatch 最新候选批次
     * @param query 查询条件
     * @param page 页码
     */
    void accept(
        List<Map<String, Object>> previewJobs,
        List<Map<String, Object>> latestBatch,
        String query,
        int page);
  }
}
