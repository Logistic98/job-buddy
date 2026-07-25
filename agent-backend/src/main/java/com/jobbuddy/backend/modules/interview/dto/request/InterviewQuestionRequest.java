package com.jobbuddy.backend.modules.interview.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 承载面试题目请求参数。
 */
@Data
public class InterviewQuestionRequest {
  private String title;
  private String content;
  private String bankType;
  private String answer;
  private String category;
  private String difficulty;
  private String questionType;
  private JsonNode tags;
  private JsonNode codingMeta;
}
