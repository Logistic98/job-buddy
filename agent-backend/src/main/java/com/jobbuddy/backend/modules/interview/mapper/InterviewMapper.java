package com.jobbuddy.backend.modules.interview.mapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 面试题库与模拟试卷记录的 MyBatis Mapper。
 */
@Mapper
public interface InterviewMapper {

  /**
   * 查询题目列表。
   *
   * @param keyword 关键词
   * @param bankType 题库类型
   * @param category 分类
   * @param difficulty 难度
   * @param limit 数量上限
   * @param offset 偏移量
   * @return 题目列表
   */
  List<Map<String, Object>> listQuestions(
      @Param("keyword") String keyword,
      @Param("bankType") String bankType,
      @Param("category") String category,
      @Param("difficulty") String difficulty,
      @Param("limit") int limit,
      @Param("offset") int offset);

  /**
   * 统计题目列表。
   *
   * @param keyword 关键词
   * @param bankType 题库类型
   * @param category 分类
   * @param difficulty 难度
   * @return 统计数量
   */
  int countQuestions(
      @Param("keyword") String keyword,
      @Param("bankType") String bankType,
      @Param("category") String category,
      @Param("difficulty") String difficulty);

  /**
   * 查找启用状态。
   *
   * @param bankType 题库类型
   * @param category 分类
   * @param difficulty 难度
   * @param questionType 题目类型
   * @return 启用状态
   */
  List<Map<String, Object>> findEnabled(
      @Param("bankType") String bankType,
      @Param("category") String category,
      @Param("difficulty") String difficulty,
      @Param("questionType") String questionType);

  /**
   * 查询题库类型。
   *
   * @return 题库类型列表
   */
  List<String> listBankTypes();

  /**
   * 查询题目分类。
   *
   * @param bankType 题库类型
   * @return 题目分类列表
   */
  List<String> listCategories(@Param("bankType") String bankType);

  /**
   * 查询难度列表。
   *
   * @param bankType 题库类型
   * @return 难度列表
   */
  List<String> listDifficulties(@Param("bankType") String bankType);

  /**
   * 查询题目类型列表。
   *
   * @param bankType 题库类型
   * @return 题目类型列表
   */
  List<String> listQuestionTypes(@Param("bankType") String bankType);

  /**
   * 查找题目。
   *
   * @param questionId 题目标识
   * @return 题目
   */
  Map<String, Object> findQuestion(@Param("questionId") String questionId);

  /**
   * 统计题目。
   *
   * @param questionId 题目标识
   * @return 统计数量
   */
  int countQuestion(@Param("questionId") Object questionId);

  /**
   * 新增题目。
   *
   * @param question 题目
   * @return 题目
   */
  int insertQuestion(@Param("question") Map<String, Object> question);

  /**
   * 更新题目。
   *
   * @param question 题目
   * @return 题目
   */
  int updateQuestion(@Param("question") Map<String, Object> question);

  /**
   * 软删除题目。
   *
   * @param questionId 题目标识
   * @param updatedAt 更新时间
   * @return 题目
   */
  int softDeleteQuestion(
      @Param("questionId") String questionId, @Param("updatedAt") Timestamp updatedAt);

  /**
   * 更新题目分类。
   *
   * @param questionId 题目标识
   * @param category 分类
   * @param updatedAt 更新时间
   * @return 题目 Category
   */
  int updateQuestionCategory(
      @Param("questionId") String questionId,
      @Param("category") Object category,
      @Param("updatedAt") Timestamp updatedAt);

  /**
   * 更新题目难度。
   *
   * @param questionId 题目标识
   * @param difficulty 难度
   * @param updatedAt 更新时间
   * @return 题目 Difficulty
   */
  int updateQuestionDifficulty(
      @Param("questionId") String questionId,
      @Param("difficulty") Object difficulty,
      @Param("updatedAt") Timestamp updatedAt);

  /**
   * 更新题目标签列表。
   *
   * @param questionId 题目标识
   * @param tagsJson 标签列表 JSON
   * @param updatedAt 更新时间
   * @return 题目 Tags
   */
  int updateQuestionTags(
      @Param("questionId") String questionId,
      @Param("tagsJson") String tagsJson,
      @Param("updatedAt") Timestamp updatedAt);

  /**
   * 新增考试。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param examId 考试标识
   * @param title 标题
   * @param status 状态
   * @param totalCount 总数数量
   * @param answeredCount 已答题数量
   * @param score 评分
   * @param durationMinutes 时长分钟
   * @param strategyJson 出题策略 JSON
   * @param startedAt 开始时间
   * @param expiresAt 过期时间
   * @return 考试
   */
  int insertExam(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("examId") String examId,
      @Param("title") String title,
      @Param("status") String status,
      @Param("totalCount") int totalCount,
      @Param("answeredCount") int answeredCount,
      @Param("score") Double score,
      @Param("durationMinutes") int durationMinutes,
      @Param("strategyJson") String strategyJson,
      @Param("startedAt") Timestamp startedAt,
      @Param("expiresAt") Timestamp expiresAt);

  /**
   * 新增考试题目。
   *
   * @param examId 考试标识
   * @param questionId 题目标识
   * @param displayOrder 展示顺序
   * @return 考试题目
   */
  int insertExamQuestion(
      @Param("examId") String examId,
      @Param("questionId") Object questionId,
      @Param("displayOrder") int displayOrder);

  /**
   * 查找考试。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param examId 考试标识
   * @return 考试
   */
  Map<String, Object> findExam(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("examId") String examId);

  /**
   * 查询待更新的考试记录。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param examId 考试标识
   * @return 待更新的考试记录
   */
  Map<String, Object> findExamForUpdate(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("examId") String examId);

  /**
   * 查询考试记录。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 考试列表
   */
  List<Map<String, Object>> listExams(
      @Param("tenantId") String tenantId, @Param("userId") String userId);

  /**
   * 查询考试题目列表。
   *
   * @param examId 考试标识
   * @return 考试题目列表
   */
  List<Map<String, Object>> examQuestions(@Param("examId") String examId);

  /**
   * 保存考试答案。
   *
   * @param examId 考试标识
   * @param questionId 题目标识
   * @param answer 答案
   * @param correct 是否回答正确
   * @param score 评分
   * @return 考试 Answer
   */
  int saveExamAnswer(
      @Param("examId") String examId,
      @Param("questionId") String questionId,
      @Param("answer") String answer,
      @Param("correct") boolean correct,
      @Param("score") double score);

  /**
   * 完成考试并写入提交结果。
   *
   * @param examId 考试标识
   * @param answeredCount 已答题数量
   * @param score 评分
   * @param submittedAt 提交时间
   * @return 受影响的记录数
   */
  int finishExam(
      @Param("examId") String examId,
      @Param("answeredCount") int answeredCount,
      @Param("score") double score,
      @Param("submittedAt") Timestamp submittedAt);
}
