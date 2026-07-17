package com.jobbuddy.backend.modules.interview.dto.response;

import java.util.List;
import lombok.Data;

@Data
public class InterviewQuestionPageResponse {
  private List<InterviewQuestionResponse> items;
  private Integer total;
  private Integer page;
  private Integer size;
  private Integer pages;
}
