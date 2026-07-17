package com.jobbuddy.backend.modules.interview.dto.response;

import java.util.List;
import lombok.Data;

@Data
public class InterviewImportResponse {
  private Integer count;
  private List<InterviewQuestionResponse> items;
}
