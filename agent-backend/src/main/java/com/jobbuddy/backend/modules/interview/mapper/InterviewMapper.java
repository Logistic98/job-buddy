package com.jobbuddy.backend.modules.interview.mapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** MyBatis mapper for interview question banks and mock exam records. */
@Mapper
public interface InterviewMapper {

  List<Map<String, Object>> listQuestions(
      @Param("keyword") String keyword,
      @Param("bankType") String bankType,
      @Param("category") String category,
      @Param("difficulty") String difficulty,
      @Param("limit") int limit,
      @Param("offset") int offset);

  int countQuestions(
      @Param("keyword") String keyword,
      @Param("bankType") String bankType,
      @Param("category") String category,
      @Param("difficulty") String difficulty);

  List<Map<String, Object>> findEnabled(
      @Param("bankType") String bankType,
      @Param("category") String category,
      @Param("difficulty") String difficulty,
      @Param("questionType") String questionType);

  List<String> listBankTypes();

  List<String> listCategories(@Param("bankType") String bankType);

  List<String> listDifficulties(@Param("bankType") String bankType);

  List<String> listQuestionTypes(@Param("bankType") String bankType);

  Map<String, Object> findQuestion(@Param("questionId") String questionId);

  int countQuestion(@Param("questionId") Object questionId);

  int insertQuestion(@Param("question") Map<String, Object> question);

  int updateQuestion(@Param("question") Map<String, Object> question);

  int softDeleteQuestion(
      @Param("questionId") String questionId, @Param("updatedAt") Timestamp updatedAt);

  int updateQuestionCategory(
      @Param("questionId") String questionId,
      @Param("category") Object category,
      @Param("updatedAt") Timestamp updatedAt);

  int updateQuestionDifficulty(
      @Param("questionId") String questionId,
      @Param("difficulty") Object difficulty,
      @Param("updatedAt") Timestamp updatedAt);

  int updateQuestionTags(
      @Param("questionId") String questionId,
      @Param("tagsJson") String tagsJson,
      @Param("updatedAt") Timestamp updatedAt);

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

  int insertExamQuestion(
      @Param("examId") String examId,
      @Param("questionId") Object questionId,
      @Param("displayOrder") int displayOrder);

  Map<String, Object> findExam(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("examId") String examId);

  Map<String, Object> findExamForUpdate(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("examId") String examId);

  List<Map<String, Object>> listExams(
      @Param("tenantId") String tenantId, @Param("userId") String userId);

  List<Map<String, Object>> examQuestions(@Param("examId") String examId);

  int saveExamAnswer(
      @Param("examId") String examId,
      @Param("questionId") String questionId,
      @Param("answer") String answer,
      @Param("correct") boolean correct,
      @Param("score") double score);

  int finishExam(
      @Param("examId") String examId,
      @Param("answeredCount") int answeredCount,
      @Param("score") double score,
      @Param("submittedAt") Timestamp submittedAt);
}
