package com.jobbuddy.backend.modules.interview.dto.response;

import java.util.List;
import lombok.Data;

/**
 * 承载面试题目分页响应数据。
 */
@Data
public class InterviewQuestionPageResponse {
  private List<InterviewQuestionResponse> items;
  private Integer total;
  private Integer page;
  private Integer size;
  private Integer pages;
}
