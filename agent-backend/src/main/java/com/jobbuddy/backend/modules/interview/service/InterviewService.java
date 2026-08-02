package com.jobbuddy.backend.modules.interview.service;

import com.jobbuddy.backend.modules.interview.dto.request.InterviewBatchRequest;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewCodeRunRequest;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewExamRequest;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewExamSubmitRequest;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewGenerateRequest;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewImportRequest;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewQuestionRequest;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewSmartExamRequest;
import com.jobbuddy.backend.modules.interview.dto.response.InterviewBatchResponse;
import com.jobbuddy.backend.modules.interview.dto.response.InterviewCodeRunResponse;
import com.jobbuddy.backend.modules.interview.dto.response.InterviewExamResponse;
import com.jobbuddy.backend.modules.interview.dto.response.InterviewExamSubmitResponse;
import com.jobbuddy.backend.modules.interview.dto.response.InterviewGenerateResponse;
import com.jobbuddy.backend.modules.interview.dto.response.InterviewImportResponse;
import com.jobbuddy.backend.modules.interview.dto.response.InterviewQuestionMetaResponse;
import com.jobbuddy.backend.modules.interview.dto.response.InterviewQuestionPageResponse;
import com.jobbuddy.backend.modules.interview.dto.response.InterviewQuestionResponse;
import java.util.List;

/**
 * 管理题库、模拟练习、答卷提交与隔离代码运行。
 *
 * <p>题库与试卷读写都受认证租户和用户约束，代码通过沙箱执行器运行，不落到 Backend 宿主机。
 */
public interface InterviewService {
  /**
   * 查询题目列表。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param keyword 关键词
   * @param category 题目分类
   * @return 题目列表
   */
  List<InterviewQuestionResponse> listQuestions(
      String tenantId, String userId, String keyword, String category);

  /**
   * 获取分页题目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param keyword 关键词
   * @param bankType 题库类型
   * @param category 题目分类
   * @param difficulty 难度
   * @param pageValue 页码值
   * @param sizeValue 数量值
   * @return 分页题目
   */
  InterviewQuestionPageResponse pageQuestions(
      String tenantId,
      String userId,
      String keyword,
      String bankType,
      String category,
      String difficulty,
      Integer pageValue,
      Integer sizeValue);

  /**
   * 获取题目元数据。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param bankType 题库类型
   * @return 题目元数据
   */
  InterviewQuestionMetaResponse questionMeta(String tenantId, String userId, String bankType);

  /**
   * 保存题目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param request 请求对象
   * @param questionId 题目标识
   * @return 保存后的题目
   */
  InterviewQuestionResponse saveQuestion(
      String tenantId, String userId, InterviewQuestionRequest request, String questionId);

  /**
   * 删除题目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param questionId 题目标识
   */
  void deleteQuestion(String tenantId, String userId, String questionId);

  /**
   * 获取批次题目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param request 请求对象
   * @return 批次题目
   */
  InterviewBatchResponse batchQuestions(
      String tenantId, String userId, InterviewBatchRequest request);

  /**
   * 导入题目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param request 请求对象
   * @return 导入后的题目列表
   */
  InterviewImportResponse importQuestions(
      String tenantId, String userId, InterviewImportRequest request);

  /**
   * 生成题目。
   *
   * @param request 请求对象
   * @return 题目
   */
  InterviewGenerateResponse generateQuestions(InterviewGenerateRequest request);

  /**
   * 创建随机结果考试。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param request 请求对象
   * @return 创建后的随机结果考试
   */
  InterviewExamResponse createRandomExam(
      String tenantId, String userId, InterviewExamRequest request);

  /**
   * 根据自然语言要求从现有题库智能选择题目并创建练习。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param request 智能组卷请求
   * @return 创建后的练习
   */
  InterviewExamResponse createSmartExam(
      String tenantId, String userId, InterviewSmartExamRequest request);

  /**
   * 获取考试。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param examId 考试标识
   * @return 考试
   */
  InterviewExamResponse getExam(String tenantId, String userId, String examId);

  /**
   * 查询考试列表。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 考试列表
   */
  List<InterviewExamResponse> listExams(String tenantId, String userId);

  /**
   * 删除用户所属练习记录。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param examId 练习标识
   */
  void deleteExam(String tenantId, String userId, String examId);

  /**
   * 提交考试。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param examId 考试标识
   * @param request 请求对象
   * @return 考试提交结果
   */
  InterviewExamSubmitResponse submitExam(
      String tenantId, String userId, String examId, InterviewExamSubmitRequest request);

  /**
   * 通过已配置的沙箱契约执行单个代码样例。
   *
   * @param request 请求对象
   * @return 执行后的编码
   */
  InterviewCodeRunResponse runCode(InterviewCodeRunRequest request);
}
