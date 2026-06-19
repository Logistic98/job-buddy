package com.jobbuddy.backend.modules.interview.dto.response;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class InterviewQuestionResponse extends MapBackedDto {
    public InterviewQuestionResponse() {
    }

    public InterviewQuestionResponse(Map<String, Object> fields) {
        super(fields);
    }

    public static InterviewQuestionResponse from(Map<String, Object> fields) {
        return new InterviewQuestionResponse(fields);
    }
}
