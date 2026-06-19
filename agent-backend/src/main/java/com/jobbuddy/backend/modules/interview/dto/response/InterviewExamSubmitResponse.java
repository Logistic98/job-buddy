package com.jobbuddy.backend.modules.interview.dto.response;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class InterviewExamSubmitResponse extends MapBackedDto {
    public InterviewExamSubmitResponse() {
    }

    public InterviewExamSubmitResponse(Map<String, Object> fields) {
        super(fields);
    }

    public static InterviewExamSubmitResponse from(Map<String, Object> fields) {
        return new InterviewExamSubmitResponse(fields);
    }
}
