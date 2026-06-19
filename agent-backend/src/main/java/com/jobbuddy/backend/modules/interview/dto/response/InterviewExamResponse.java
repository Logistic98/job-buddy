package com.jobbuddy.backend.modules.interview.dto.response;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class InterviewExamResponse extends MapBackedDto {
    public InterviewExamResponse() {
    }

    public InterviewExamResponse(Map<String, Object> fields) {
        super(fields);
    }

    public static InterviewExamResponse from(Map<String, Object> fields) {
        return new InterviewExamResponse(fields);
    }
}
