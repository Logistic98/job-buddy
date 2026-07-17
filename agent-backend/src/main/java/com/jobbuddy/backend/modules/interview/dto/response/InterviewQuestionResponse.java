package com.jobbuddy.backend.modules.interview.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InterviewQuestionResponse {
  private String questionId;
  private String bankType;
  private String title;
  private String category;
  private String difficulty;
  private String questionType;
  private String content;
  private String answer;
  private List<Tag> tags;
  private JsonNode codingMeta;
  private Boolean enabled;
  private Instant createdAt;
  private Instant updatedAt;
  private String userAnswer;
  private Boolean correct;
  private Double score;
  private Integer displayOrder;

  @Data
  public static class Tag {
    private String label;
  }
}
