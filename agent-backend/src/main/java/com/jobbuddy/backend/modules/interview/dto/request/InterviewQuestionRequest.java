package com.jobbuddy.backend.modules.interview.dto.request;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class InterviewQuestionRequest extends MapBackedDto {
    public InterviewQuestionRequest() {
    }

    public InterviewQuestionRequest(Map<String, Object> fields) {
        super(fields);
    }

    public static InterviewQuestionRequest from(Map<String, Object> fields) {
        return new InterviewQuestionRequest(fields);
    }
}
