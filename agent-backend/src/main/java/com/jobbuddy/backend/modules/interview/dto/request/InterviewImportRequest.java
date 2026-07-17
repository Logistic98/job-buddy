package com.jobbuddy.backend.modules.interview.dto.request;

import java.util.List;
import lombok.Data;

@Data
public class InterviewImportRequest {
  private List<InterviewQuestionRequest> items;
}
