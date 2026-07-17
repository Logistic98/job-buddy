package com.jobbuddy.backend.modules.interview.service;

import com.jobbuddy.backend.modules.interview.dto.request.InterviewBatchRequest;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewCodeRunRequest;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewExamRequest;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewExamSubmitRequest;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewGenerateRequest;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewImportRequest;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewQuestionRequest;
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

public interface InterviewService {
  List<InterviewQuestionResponse> listQuestions(String keyword, String category);

  InterviewQuestionPageResponse pageQuestions(
      String keyword,
      String bankType,
      String category,
      String difficulty,
      Integer pageValue,
      Integer sizeValue);

  InterviewQuestionMetaResponse questionMeta(String bankType);

  InterviewQuestionResponse saveQuestion(InterviewQuestionRequest request, String questionId);

  void deleteQuestion(String questionId);

  InterviewBatchResponse batchQuestions(InterviewBatchRequest request);

  InterviewImportResponse importQuestions(InterviewImportRequest request);

  InterviewGenerateResponse generateQuestions(InterviewGenerateRequest request);

  InterviewExamResponse createRandomExam(
      String tenantId, String userId, InterviewExamRequest request);

  InterviewExamResponse getExam(String tenantId, String userId, String examId);

  List<InterviewExamResponse> listExams(String tenantId, String userId);

  InterviewExamSubmitResponse submitExam(
      String tenantId, String userId, String examId, InterviewExamSubmitRequest request);

  InterviewCodeRunResponse runCode(InterviewCodeRunRequest request);
}
