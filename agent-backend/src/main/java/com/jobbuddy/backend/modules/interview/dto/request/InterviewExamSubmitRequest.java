package com.jobbuddy.backend.modules.interview.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class InterviewExamSubmitRequest {
  private JsonNode answers;
  private JsonNode codingResults;
}
