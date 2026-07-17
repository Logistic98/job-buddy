package com.jobbuddy.backend.modules.interview.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InterviewExamResponse {
  private String examId;
  private String practiceId;
  private String title;
  private String status;
  private Integer totalCount;
  private Integer answeredCount;
  private Double score;
  private Integer durationMinutes;
  private JsonNode strategy;
  private Instant startedAt;
  private Instant expiresAt;
  private Instant submittedAt;
  private Long remainingSeconds;
  private List<InterviewQuestionResponse> questions;
}
