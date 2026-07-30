package com.jobbuddy.backend.modules.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobbuddy.backend.modules.auth.dto.internal.BossCliCancelResult;
import com.jobbuddy.backend.modules.auth.dto.internal.BossCliQrResult;
import com.jobbuddy.backend.modules.auth.dto.internal.BossCliStatusResult;
import com.jobbuddy.backend.modules.auth.dto.internal.BossFavoriteListResult;
import com.jobbuddy.backend.modules.auth.dto.response.BossLoginStatusResponse;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import java.util.List;
import java.util.Map;

/**
 * 定义 Boss CLI 服务契约。
 */
public interface BossCliService {
  /**
   * 获取状态。
   *
   * @return 当前状态
   */
  BossCliStatusResult status();

  /**
   * 判断已认证用户。
   *
   * @return 用户是否已认证
   */
  boolean isAuthenticated();

  /**
   * 获取登录指令。
   *
   * @return 登录指令
   */
  BossLoginStatusResponse loginInstructions();

  /**
   * 启动二维码登录。
   *
   * @return 二维码登录启动结果
   */
  BossCliQrResult qrStart();

  /**
   * 获取二维码状态。
   *
   * @param sessionId 会话标识
   * @param sessionToken 会话令牌
   * @return 二维码状态
   */
  BossCliQrResult qrStatus(String sessionId, String sessionToken);

  /**
   * 获取二维码本地快照，不等待上游扫码长轮询。
   *
   * @param sessionId 会话标识
   * @param sessionToken 会话令牌
   * @return 二维码当前快照
   */
  BossCliQrResult qrSnapshot(String sessionId, String sessionToken);

  /**
   * 取消二维码登录。
   *
   * @param sessionId 会话标识
   * @param sessionToken 会话令牌
   * @return 二维码登录取消结果
   */
  BossCliCancelResult qrCancel(String sessionId, String sessionToken);

  /**
   * 取消登录。
   *
   * @return 登录取消结果
   */
  BossCliCancelResult cancelLogin();

  // 画像、岗位详情和搜索结果来自 agent-tool/Boss，字段不稳定，属于明确外部 JSON 边界。
  /**
   * 获取在线数据画像。
   *
   * @return 在线数据画像
   */
  JsonNode fetchOnlineProfile();

  /**
   * 获取岗位详情。
   *
   * @param securityId Boss 岗位安全标识
   * @param url 请求地址
   * @return 岗位详情
   */
  JsonNode jobDetail(String securityId, String url);

  /**
   * 获取收藏岗位列表。
   *
   * @param page 页码
   * @return 收藏岗位列表
   */
  BossFavoriteListResult favoriteJobs(int page);

  /**
   * 获取收藏岗位列表。
   *
   * @param page 页码
   * @param forceRefresh 是否强制刷新
   * @return 收藏岗位列表
   */
  BossFavoriteListResult favoriteJobs(int page, boolean forceRefresh);

  /**
   * 检索岗位。
   *
   * @param intent 意图
   * @return 岗位搜索结果
   */
  List<Map<String, Object>> searchJobs(IntentResult intent);

  /**
   * 检索岗位。
   *
   * @param intent 意图
   * @param targetCount 目标数量
   * @return 岗位搜索结果
   */
  List<Map<String, Object>> searchJobs(IntentResult intent, int targetCount);

  /**
   * 检索岗位首个分页。
   *
   * @param intent 意图
   * @return 岗位搜索首页结果
   */
  List<Map<String, Object>> searchJobsFirstPage(IntentResult intent);

  /**
   * 检索岗位分页。
   *
   * @param intent 意图
   * @param page 页码
   * @return 岗位搜索分页结果
   */
  List<Map<String, Object>> searchJobsPage(IntentResult intent, int page);

  /**
   * 分批检索岗位。
   *
   * @param intent 意图
   * @param targetCount 目标数量
   * @param consumer 结果消费函数
   * @return 岗位搜索批次列表
   */
  List<Map<String, Object>> searchJobsBatches(
      IntentResult intent, int targetCount, JobBatchConsumer consumer);

  /**
   * 补充岗位详情。
   *
   * @param jobs 岗位列表
   * @param maxDetails 最大详情数量
   * @return 补全岗位详情列表
   */
  List<Map<String, Object>> enrichJobDetails(List<Map<String, Object>> jobs, int maxDetails);

  /**
   * 定义岗位批次消费者。
   */
  interface JobBatchConsumer {
    /**
     * 接收并处理输入。
     *
     * @param accumulated 累计文本
     * @param latestBatch 最新候选批次
     * @param query 查询条件
     * @param page 页码
     */
    void accept(
        List<Map<String, Object>> accumulated,
        List<Map<String, Object>> latestBatch,
        String query,
        int page);
  }
}
