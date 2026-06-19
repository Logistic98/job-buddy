package com.jobbuddy.backend.modules.interview.dto.response;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class InterviewQuestionPageResponse extends MapBackedDto {
    public InterviewQuestionPageResponse() {
    }

    public InterviewQuestionPageResponse(Map<String, Object> fields) {
        super(fields);
    }

    public static InterviewQuestionPageResponse from(Map<String, Object> fields) {
        return new InterviewQuestionPageResponse(fields);
    }
}
