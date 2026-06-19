package com.jobbuddy.backend.modules.interview.service;

import java.util.List;
import java.util.Map;

public interface InterviewService {
    List<Map<String, Object>> listQuestions(String keyword, String category);
    Map<String, Object> pageQuestions(String keyword, String bankType, String category, String difficulty, Integer pageValue, Integer sizeValue);
    Map<String, Object> questionMeta(String bankType);
    Map<String, Object> saveQuestion(Map<String, Object> payload, String questionId);
    void deleteQuestion(String questionId);
    Map<String, Object> batchQuestions(Map<String, Object> payload);
    Map<String, Object> importQuestions(Map<String, Object> payload);
    Map<String, Object> generateQuestions(Map<String, Object> payload);
    Map<String, Object> createRandomExam(Map<String, Object> payload);
    Map<String, Object> getExam(String examId);
    List<Map<String, Object>> listExams();
    Map<String, Object> submitExam(String examId, Map<String, Object> payload);
    Map<String, Object> runCode(Map<String, Object> payload);
}
